package com.mundiapolis.library.notification.adapter.`in`.worker

import com.mundiapolis.library.notification.config.EmailDeliveryProperties
import com.mundiapolis.library.notification.service.EmailDeliveryService
import com.mundiapolis.library.notification.service.EmailDeliveryStore
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class EmailDeliveryWorker(
    private val service: EmailDeliveryService,
    private val store: EmailDeliveryStore,
    private val properties: EmailDeliveryProperties,
    meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val delivered = counter(meterRegistry, "delivered")
    private val suppressed = counter(meterRegistry, "suppressed")
    private val retryScheduled = counter(meterRegistry, "retry_scheduled")
    private val deadLettered = counter(meterRegistry, "dead_lettered")
    private val claimLost = counter(meterRegistry, "claim_lost")
    private val cycleFailures = counter(meterRegistry, "cycle_failed")
    private val pendingGauge = gauge(meterRegistry, "pending")
    private val deliveringGauge = gauge(meterRegistry, "delivering")
    private val retryingGauge = gauge(meterRegistry, "retrying")
    private val deadLetterGauge = gauge(meterRegistry, "dead_lettered")
    private val expiredLeaseGauge = gauge(meterRegistry, "expired_leases")
    private val oldestPendingAgeGauge = gauge(meterRegistry, "oldest_pending_age_seconds")

    @Volatile
    private var snapshot = EmailDeliveryHealthSnapshot(false, 0, 0, 0, 0, 0)

    @Scheduled(fixedDelayString = "\${app.email-worker.poll-interval}")
    fun poll() {
        try {
            val cycle = service.deliverBatch()
            delivered.increment(cycle.delivered.toDouble())
            suppressed.increment(cycle.suppressed.toDouble())
            retryScheduled.increment(cycle.retryScheduled.toDouble())
            deadLettered.increment(cycle.deadLettered.toDouble())
            claimLost.increment(cycle.claimLost.toDouble())
            refreshStatistics()
        } catch (_: Exception) {
            cycleFailures.increment()
            logger.error("Email delivery cycle failed")
        }
    }

    fun healthSnapshot(): EmailDeliveryHealthSnapshot {
        refreshStatistics()
        return snapshot
    }

    private fun refreshStatistics() {
        val now = clock.instant()
        val statistics = store.statistics(now)
        val oldestAge = statistics.oldestPendingAt
            ?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
            ?: 0
        pendingGauge.set(statistics.pending)
        deliveringGauge.set(statistics.delivering)
        retryingGauge.set(statistics.retrying)
        deadLetterGauge.set(statistics.deadLettered)
        expiredLeaseGauge.set(statistics.expiredLeases)
        oldestPendingAgeGauge.set(oldestAge)
        snapshot = EmailDeliveryHealthSnapshot(
            backlogWithinObjective = statistics.expiredLeases == 0L &&
                oldestAge <= properties.maximumPendingAge.seconds,
            pending = statistics.pending,
            retrying = statistics.retrying,
            deadLettered = statistics.deadLettered,
            expiredLeases = statistics.expiredLeases,
            oldestPendingAgeSeconds = oldestAge,
        )
    }

    private fun counter(registry: MeterRegistry, outcome: String): Counter = Counter.builder(CYCLE_METRIC)
        .tag("outcome", outcome)
        .register(registry)

    private fun gauge(registry: MeterRegistry, state: String): AtomicLong = AtomicLong().also { value ->
        Gauge.builder(BACKLOG_METRIC, value) { it.get().toDouble() }
            .tag("state", state)
            .register(registry)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(EmailDeliveryWorker::class.java)
        const val CYCLE_METRIC = "mundia.notification.email.delivery.outcomes"
        const val BACKLOG_METRIC = "mundia.notification.email.delivery.backlog"
    }
}

data class EmailDeliveryHealthSnapshot(
    val backlogWithinObjective: Boolean,
    val pending: Long,
    val retrying: Long,
    val deadLettered: Long,
    val expiredLeases: Long,
    val oldestPendingAgeSeconds: Long,
)
