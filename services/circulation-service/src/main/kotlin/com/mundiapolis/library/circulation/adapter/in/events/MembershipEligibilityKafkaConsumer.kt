package com.mundiapolis.library.circulation.adapter.`in`.events

import com.mundiapolis.library.circulation.application.model.EligibilityEventDisposition
import com.mundiapolis.library.circulation.application.model.MembershipEventClockSkewException
import com.mundiapolis.library.circulation.application.model.MembershipEventConflictException
import com.mundiapolis.library.circulation.application.model.MembershipEventGapException
import com.mundiapolis.library.circulation.application.port.inbound.ApplyMembershipEligibilityEventUseCase
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.config.MembershipEligibilityConsumerProperties
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MembershipEligibilityKafkaConsumer(
    private val consumer: Consumer<String, ByteArray>,
    private val decoder: MembershipEligibilityRecordDecoder,
    private val applyEligibilityEvent: ApplyMembershipEligibilityEventUseCase,
    private val timeProvider: TimeProvider,
    private val properties: MembershipEligibilityConsumerProperties,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private val assignmentCount = AtomicInteger()
    private val lastProcessedOffset = AtomicLong(-1)
    private val startedAt = AtomicReference<Instant>()
    private val lastSuccessfulPoll = AtomicReference<Instant>()
    private val fatalFailure = AtomicReference<MembershipConsumerFailure?>()
    private val executor = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("membership-eligibility-consumer").factory(),
    )
    private val processedCounter = meterRegistry.counter("mundia.membership.consumer.processed")
    private val replayCounter = meterRegistry.counter("mundia.membership.consumer.replayed")
    private val staleCounter = meterRegistry.counter("mundia.membership.consumer.stale")
    private val failureCounter = meterRegistry.counter("mundia.membership.consumer.failures")
    private val retryCounter = meterRegistry.counter("mundia.membership.consumer.retries")
    private var worker: Future<*>? = null

    init {
        meterRegistry.gauge("mundia.membership.consumer.assignments", assignmentCount)
        meterRegistry.gauge("mundia.membership.consumer.last.processed.offset", lastProcessedOffset)
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt.set(timeProvider.now())
        fatalFailure.set(null)
        worker = executor.submit(::pollUntilStopped)
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            consumer.wakeup()
        }
        runCatching { worker?.get(STOP_TIMEOUT.seconds, TimeUnit.SECONDS) }
        executor.shutdown()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 0

    fun healthSnapshot(now: Instant): MembershipConsumerHealthSnapshot {
        val failure = fatalFailure.get()
        val started = startedAt.get()
        val lastPoll = lastSuccessfulPoll.get()
        val assignments = assignmentCount.get()
        val withinStartupGrace = started != null &&
            Duration.between(started, now) <= properties.startupGracePeriod
        val pollStale = lastPoll != null &&
            Duration.between(lastPoll, now) > properties.maximumPollSilence
        return MembershipConsumerHealthSnapshot(
            ready = failure == null && assignments > 0 && !pollStale,
            starting = failure == null && assignments == 0 && withinStartupGrace,
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
                lastSuccessfulPoll.set(timeProvider.now())
                for (record in records) {
                    if (!running.get()) break
                    val execution = applyEligibilityEvent.apply(decoder.decode(record))
                    if (!commit(record.topic(), record.partition(), record.offset() + 1)) break
                    processedCounter.increment()
                    if (execution.replayed) replayCounter.increment()
                    if (execution.disposition == EligibilityEventDisposition.STALE) {
                        staleCounter.increment()
                    }
                    lastProcessedOffset.set(record.offset())
                }
            }
        } catch (_: WakeupException) {
            if (running.get()) fail(MembershipConsumerFailure.BROKER)
        } catch (_: MembershipEventContractException) {
            fail(MembershipConsumerFailure.CONTRACT)
        } catch (_: MembershipEventGapException) {
            fail(MembershipConsumerFailure.EVENT_GAP)
        } catch (_: MembershipEventConflictException) {
            fail(MembershipConsumerFailure.EVENT_CONFLICT)
        } catch (_: MembershipEventClockSkewException) {
            fail(MembershipConsumerFailure.EVENT_CLOCK_SKEW)
        } catch (_: AuthenticationException) {
            fail(MembershipConsumerFailure.BROKER_AUTHENTICATION)
        } catch (_: AuthorizationException) {
            fail(MembershipConsumerFailure.BROKER_AUTHORIZATION)
        } catch (_: Exception) {
            fail(MembershipConsumerFailure.INTERNAL)
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
        logger.warn(
            "Transient membership eligibility broker failure; retrying after {}",
            properties.retryBackoff,
            failure,
        )
        try {
            Thread.sleep(properties.retryBackoff)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running.set(false)
        }
    }

    private fun fail(failure: MembershipConsumerFailure) {
        fatalFailure.compareAndSet(null, failure)
        failureCounter.increment()
        logger.error("Membership eligibility consumer stopped: {}", failure)
    }

    private companion object {
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
        val logger = LoggerFactory.getLogger(MembershipEligibilityKafkaConsumer::class.java)
    }
}

data class MembershipConsumerHealthSnapshot(
    val ready: Boolean,
    val starting: Boolean,
    val assignments: Int,
    val lastProcessedOffset: Long,
    val failure: MembershipConsumerFailure?,
)

enum class MembershipConsumerFailure {
    CONTRACT,
    EVENT_GAP,
    EVENT_CONFLICT,
    EVENT_CLOCK_SKEW,
    BROKER_AUTHENTICATION,
    BROKER_AUTHORIZATION,
    BROKER,
    INTERNAL,
}
