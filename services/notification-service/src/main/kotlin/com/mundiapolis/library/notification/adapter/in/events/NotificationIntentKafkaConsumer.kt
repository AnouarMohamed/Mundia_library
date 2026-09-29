package com.mundiapolis.library.notification.adapter.`in`.events

import com.mundiapolis.library.notification.config.NotificationIntentConsumerProperties
import com.mundiapolis.library.notification.dto.NotificationIntentClockSkewException
import com.mundiapolis.library.notification.dto.NotificationIntentConflictException
import com.mundiapolis.library.notification.service.NotificationIntentHandler
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.WakeupException
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class NotificationIntentKafkaConsumer(
    private val consumer: Consumer<String, ByteArray>,
    private val decoder: NotificationIntentRecordDecoder,
    private val handler: NotificationIntentHandler,
    private val clock: Clock,
    private val properties: NotificationIntentConsumerProperties,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private val assignmentCount = AtomicInteger()
    private val lastProcessedOffset = AtomicLong(-1)
    private val startedAt = AtomicReference<Instant>()
    private val lastSuccessfulPoll = AtomicReference<Instant>()
    private val fatalFailure = AtomicReference<NotificationIntentConsumerFailure?>()
    private val executor = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("notification-intent-consumer").factory(),
    )
    private val processedCounter = meterRegistry.counter("mundia.notification.intent.consumer.processed")
    private val replayCounter = meterRegistry.counter("mundia.notification.intent.consumer.replayed")
    private val failureCounter = meterRegistry.counter("mundia.notification.intent.consumer.failures")
    private val retryCounter = meterRegistry.counter("mundia.notification.intent.consumer.retries")
    private var worker: Future<*>? = null

    init {
        meterRegistry.gauge("mundia.notification.intent.consumer.assignments", assignmentCount)
        meterRegistry.gauge("mundia.notification.intent.consumer.last.processed.offset", lastProcessedOffset)
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt.set(clock.instant())
        fatalFailure.set(null)
        worker = executor.submit(::pollUntilStopped)
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) consumer.wakeup()
        runCatching { worker?.get(STOP_TIMEOUT.seconds, TimeUnit.SECONDS) }
        executor.shutdown()
    }

    override fun isRunning(): Boolean = running.get()
    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = 0

    fun healthSnapshot(now: Instant): NotificationIntentConsumerHealthSnapshot {
        val failure = fatalFailure.get()
        val started = startedAt.get()
        val lastPoll = lastSuccessfulPoll.get()
        val assignments = assignmentCount.get()
        val starting = failure == null && assignments == 0 && started != null &&
            Duration.between(started, now) <= properties.startupGracePeriod
        val pollStale = lastPoll != null && Duration.between(lastPoll, now) > properties.maximumPollSilence
        return NotificationIntentConsumerHealthSnapshot(
            ready = failure == null && assignments > 0 && !pollStale,
            starting = starting,
            assignments = assignments,
            lastProcessedOffset = lastProcessedOffset.get(),
            failure = failure,
        )
    }

    private fun pollUntilStopped() {
        try {
            consumer.subscribe(listOf(properties.topic))
            while (running.get()) {
                val records = try {
                    consumer.poll(properties.pollTimeout)
                } catch (failure: RetriableException) {
                    retryAfterBrokerFailure(failure)
                    continue
                }
                assignmentCount.set(consumer.assignment().size)
                lastSuccessfulPoll.set(clock.instant())
                for (record in records) {
                    if (!running.get()) break
                    val execution = handler.apply(decoder.decode(record))
                    processedCounter.increment()
                    if (execution.replayed) replayCounter.increment()
                    if (!commit(record.topic(), record.partition(), record.offset() + 1)) break
                    lastProcessedOffset.set(record.offset())
                }
            }
        } catch (failure: WakeupException) {
            if (running.get()) fail(NotificationIntentConsumerFailure.BROKER, failure)
        } catch (failure: NotificationIntentContractException) {
            fail(NotificationIntentConsumerFailure.CONTRACT, failure)
        } catch (failure: NotificationIntentConflictException) {
            fail(NotificationIntentConsumerFailure.EVENT_CONFLICT, failure)
        } catch (failure: NotificationIntentClockSkewException) {
            fail(NotificationIntentConsumerFailure.EVENT_CLOCK_SKEW, failure)
        } catch (failure: AuthenticationException) {
            fail(NotificationIntentConsumerFailure.BROKER_AUTHENTICATION, failure)
        } catch (failure: AuthorizationException) {
            fail(NotificationIntentConsumerFailure.BROKER_AUTHORIZATION, failure)
        } catch (failure: Exception) {
            fail(NotificationIntentConsumerFailure.INTERNAL, failure)
        } finally {
            running.set(false)
            runCatching { consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT)) }
        }
    }

    private fun commit(topic: String, partition: Int, nextOffset: Long): Boolean {
        while (running.get()) {
            try {
                consumer.commitSync(
                    mapOf(TopicPartition(topic, partition) to OffsetAndMetadata(nextOffset)),
                    properties.commitTimeout,
                )
                return true
            } catch (failure: RetriableException) {
                retryAfterBrokerFailure(failure)
            }
        }
        return false
    }

    private fun retryAfterBrokerFailure(failure: RetriableException) {
        retryCounter.increment()
        logger.warn("Transient notification intent consumer failure; retrying", failure)
        try {
            Thread.sleep(properties.retryBackoff)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running.set(false)
        }
    }

    private fun fail(failure: NotificationIntentConsumerFailure, cause: Throwable) {
        fatalFailure.compareAndSet(null, failure)
        failureCounter.increment()
        logger.error("Notification intent consumer stopped: {}", failure, cause)
    }

    private companion object {
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
        val logger = LoggerFactory.getLogger(NotificationIntentKafkaConsumer::class.java)
    }
}

data class NotificationIntentConsumerHealthSnapshot(
    val ready: Boolean,
    val starting: Boolean,
    val assignments: Int,
    val lastProcessedOffset: Long,
    val failure: NotificationIntentConsumerFailure?,
)

enum class NotificationIntentConsumerFailure {
    CONTRACT,
    EVENT_CONFLICT,
    EVENT_CLOCK_SKEW,
    BROKER_AUTHENTICATION,
    BROKER_AUTHORIZATION,
    BROKER,
    INTERNAL,
}
