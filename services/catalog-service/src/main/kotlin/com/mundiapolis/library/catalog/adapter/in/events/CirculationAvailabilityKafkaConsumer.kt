package com.mundiapolis.library.catalog.adapter.`in`.events

import com.mundiapolis.library.catalog.config.CirculationConsumerProperties
import com.mundiapolis.library.catalog.dto.CirculationEventClockSkewException
import com.mundiapolis.library.catalog.dto.CirculationEventConflictException
import com.mundiapolis.library.catalog.dto.CirculationEventGapException
import com.mundiapolis.library.catalog.dto.ConsumerEventDisposition
import com.mundiapolis.library.catalog.dto.DecodedCirculationRecord
import com.mundiapolis.library.catalog.service.CirculationAvailabilityEventHandler
import com.mundiapolis.library.catalog.service.CirculationLoanEventHandler
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

class CirculationAvailabilityKafkaConsumer(
    private val consumer: Consumer<String, ByteArray>,
    private val decoder: CirculationEventRecordDecoder,
    private val projectionService: CirculationAvailabilityEventHandler,
    private val loanProjectionService: CirculationLoanEventHandler,
    private val clock: Clock,
    private val properties: CirculationConsumerProperties,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private val assignmentCount = AtomicInteger()
    private val lastProcessedOffset = AtomicLong(-1)
    private val startedAt = AtomicReference<Instant>()
    private val lastSuccessfulPoll = AtomicReference<Instant>()
    private val fatalFailure = AtomicReference<CirculationConsumerFailure?>()
    private val executor = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("catalog-circulation-consumer").factory(),
    )
    private val processedCounter = meterRegistry.counter("mundia.circulation.consumer.processed")
    private val ignoredCounter = meterRegistry.counter("mundia.circulation.consumer.ignored")
    private val replayCounter = meterRegistry.counter("mundia.circulation.consumer.replayed")
    private val staleCounter = meterRegistry.counter("mundia.circulation.consumer.stale")
    private val failureCounter = meterRegistry.counter("mundia.circulation.consumer.failures")
    private val retryCounter = meterRegistry.counter("mundia.circulation.consumer.retries")
    private var worker: Future<*>? = null

    init {
        meterRegistry.gauge("mundia.circulation.consumer.assignments", assignmentCount)
        meterRegistry.gauge("mundia.circulation.consumer.last.processed.offset", lastProcessedOffset)
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

    fun healthSnapshot(now: Instant): CirculationConsumerHealthSnapshot {
        val failure = fatalFailure.get()
        val started = startedAt.get()
        val lastPoll = lastSuccessfulPoll.get()
        val assignments = assignmentCount.get()
        val starting = failure == null && assignments == 0 && started != null &&
            Duration.between(started, now) <= properties.startupGracePeriod
        val pollStale = lastPoll != null && Duration.between(lastPoll, now) > properties.maximumPollSilence
        return CirculationConsumerHealthSnapshot(
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
                    when (val decoded = decoder.decode(record)) {
                        is DecodedCirculationRecord.Copy -> {
                            val execution = projectionService.apply(decoded.event)
                            processedCounter.increment()
                            if (execution.replayed) replayCounter.increment()
                            if (execution.disposition == ConsumerEventDisposition.STALE) staleCounter.increment()
                        }
                        is DecodedCirculationRecord.Loan -> {
                            val execution = loanProjectionService.apply(decoded.event)
                            processedCounter.increment()
                            if (execution.replayed) replayCounter.increment()
                            if (execution.disposition == ConsumerEventDisposition.STALE) staleCounter.increment()
                        }
                        DecodedCirculationRecord.Ignored -> ignoredCounter.increment()
                    }
                    if (!commit(record.topic(), record.partition(), record.offset() + 1)) break
                    lastProcessedOffset.set(record.offset())
                }
            }
        } catch (_: WakeupException) {
            if (running.get()) fail(CirculationConsumerFailure.BROKER)
        } catch (_: CirculationEventContractException) {
            fail(CirculationConsumerFailure.CONTRACT)
        } catch (_: CirculationEventGapException) {
            fail(CirculationConsumerFailure.EVENT_GAP)
        } catch (_: CirculationEventConflictException) {
            fail(CirculationConsumerFailure.EVENT_CONFLICT)
        } catch (_: CirculationEventClockSkewException) {
            fail(CirculationConsumerFailure.EVENT_CLOCK_SKEW)
        } catch (_: AuthenticationException) {
            fail(CirculationConsumerFailure.BROKER_AUTHENTICATION)
        } catch (_: AuthorizationException) {
            fail(CirculationConsumerFailure.BROKER_AUTHORIZATION)
        } catch (_: Exception) {
            fail(CirculationConsumerFailure.INTERNAL)
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
        logger.warn("Transient Catalog circulation consumer failure; retrying", failure)
        try {
            Thread.sleep(properties.retryBackoff)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running.set(false)
        }
    }

    private fun fail(failure: CirculationConsumerFailure) {
        fatalFailure.compareAndSet(null, failure)
        failureCounter.increment()
        logger.error("Catalog circulation consumer stopped: {}", failure)
    }

    private companion object {
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
        val logger = LoggerFactory.getLogger(CirculationAvailabilityKafkaConsumer::class.java)
    }
}

data class CirculationConsumerHealthSnapshot(
    val ready: Boolean,
    val starting: Boolean,
    val assignments: Int,
    val lastProcessedOffset: Long,
    val failure: CirculationConsumerFailure?,
)

enum class CirculationConsumerFailure {
    CONTRACT,
    EVENT_GAP,
    EVENT_CONFLICT,
    EVENT_CLOCK_SKEW,
    BROKER_AUTHENTICATION,
    BROKER_AUTHORIZATION,
    BROKER,
    INTERNAL,
}
