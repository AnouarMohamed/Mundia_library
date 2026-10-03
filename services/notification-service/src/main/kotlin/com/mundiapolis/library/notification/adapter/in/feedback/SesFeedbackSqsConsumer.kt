package com.mundiapolis.library.notification.adapter.`in`.feedback

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import com.mundiapolis.library.notification.config.SesFeedbackProperties
import com.mundiapolis.library.notification.dto.SesFeedbackContractException
import com.mundiapolis.library.notification.dto.SesFeedbackConflictException
import com.mundiapolis.library.notification.dto.SesFeedbackCorrelationException
import com.mundiapolis.library.notification.dto.SesFeedbackTransientException
import com.mundiapolis.library.notification.service.SesFeedbackService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SesFeedbackQueueMessage(val messageId: String, val receiptHandle: String, val body: String)

interface SesFeedbackQueue {
    fun receive(): List<SesFeedbackQueueMessage>
    fun delete(receiptHandle: String)
}

class AwsSqsSesFeedbackQueue(
    private val client: SqsClient,
    private val properties: SesFeedbackProperties,
) : SesFeedbackQueue {
    override fun receive(): List<SesFeedbackQueueMessage> = runBlocking {
        client.receiveMessage(ReceiveMessageRequest {
            queueUrl = properties.queueUrl
            maxNumberOfMessages = 10
            waitTimeSeconds = properties.waitTime.seconds.toInt()
            visibilityTimeout = properties.visibilityTimeout.seconds.toInt()
        }).messages.orEmpty().map { message ->
            val messageId = message.messageId
                ?: throw SesFeedbackContractException("SQS message identifier is absent")
            val receipt = message.receiptHandle
                ?: throw SesFeedbackContractException("SQS receipt handle is absent")
            val body = message.body ?: throw SesFeedbackContractException("SQS message body is absent")
            if (messageId.length !in 1..128 || receipt.length !in 1..4_096) {
                throw SesFeedbackContractException("SQS message metadata is invalid")
            }
            SesFeedbackQueueMessage(messageId, receipt, body)
        }
    }

    override fun delete(receiptHandle: String): Unit = runBlocking {
        client.deleteMessage(DeleteMessageRequest {
            queueUrl = properties.queueUrl
            this.receiptHandle = receiptHandle
        })
    }
}

class SesFeedbackSqsConsumer(
    private val queue: SesFeedbackQueue,
    private val decoder: SesFeedbackMessageDecoder,
    private val service: SesFeedbackService,
    private val properties: SesFeedbackProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private val startedAt = AtomicReference<Instant>()
    private val lastSuccessfulPoll = AtomicReference<Instant>()
    private val consecutiveFailures = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ses-feedback-consumer").factory())
    private val processed = counter(meterRegistry, "processed")
    private val replayed = counter(meterRegistry, "replayed")
    private val rejected = counter(meterRegistry, "rejected")
    private val failures = counter(meterRegistry, "failed")
    private var worker: Future<*>? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt.set(clock.instant())
        worker = executor.submit(::pollUntilStopped)
    }

    override fun stop() {
        running.set(false)
        runCatching { worker?.get(STOP_TIMEOUT.seconds, TimeUnit.SECONDS) }
        executor.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()
    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = 0

    fun healthSnapshot(now: Instant): SesFeedbackHealthSnapshot {
        val started = startedAt.get()
        val lastPoll = lastSuccessfulPoll.get()
        val starting = started != null && lastPoll == null && Duration.between(started, now) <= properties.startupGracePeriod
        val stale = lastPoll == null || Duration.between(lastPoll, now) > properties.maximumPollSilence
        val failureCount = consecutiveFailures.get()
        return SesFeedbackHealthSnapshot(
            ready = running.get() && !stale && failureCount < properties.maximumConsecutiveFailures,
            starting = starting,
            consecutiveFailures = failureCount,
            lastSuccessfulPoll = lastPoll,
        )
    }

    private fun pollUntilStopped() {
        while (running.get()) {
            try {
                val messages = queue.receive()
                lastSuccessfulPoll.set(clock.instant())
                if (messages.isEmpty()) consecutiveFailures.set(0)
                messages.forEach { message -> if (running.get()) process(message) }
            } catch (_: Exception) {
                failures.increment()
                consecutiveFailures.incrementAndGet()
                logger.error("SES feedback queue poll failed")
                retryBackoff()
            }
        }
    }

    internal fun process(message: SesFeedbackQueueMessage) {
        try {
            val result = service.apply(decoder.decode(message.body))
            queue.delete(message.receiptHandle)
            processed.increment()
            if (result.replayed) replayed.increment()
            consecutiveFailures.set(0)
        } catch (_: SesFeedbackContractException) {
            rejected.increment()
            logger.warn("Rejected SES feedback message {}; SQS redrive policy will quarantine it", message.messageId)
        } catch (_: SesFeedbackConflictException) {
            rejected.increment()
            logger.warn("Conflicting SES feedback replay {}; SQS redrive policy will quarantine it", message.messageId)
        } catch (_: SesFeedbackCorrelationException) {
            rejected.increment()
            logger.warn("Unmatched SES feedback message {}; SQS will retry before redrive", message.messageId)
        } catch (_: SesFeedbackTransientException) {
            failures.increment()
            consecutiveFailures.incrementAndGet()
            logger.warn("Transient SES feedback processing failure for message {}", message.messageId)
        } catch (_: Exception) {
            failures.increment()
            consecutiveFailures.incrementAndGet()
            logger.error("SES feedback processing failed for message {}", message.messageId)
        }
    }

    private fun retryBackoff() {
        try {
            Thread.sleep(properties.retryBackoff)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running.set(false)
        }
    }

    private fun counter(registry: MeterRegistry, outcome: String): Counter = Counter.builder(METRIC)
        .tag("outcome", outcome)
        .register(registry)

    private companion object {
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(25)
        val logger = LoggerFactory.getLogger(SesFeedbackSqsConsumer::class.java)
        const val METRIC = "mundia.notification.ses.feedback.outcomes"
    }
}

data class SesFeedbackHealthSnapshot(
    val ready: Boolean,
    val starting: Boolean,
    val consecutiveFailures: Int,
    val lastSuccessfulPoll: Instant?,
)
