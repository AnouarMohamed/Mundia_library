package com.mundiapolis.library.digitalcontent.adapter.`in`.promotion

import com.mundiapolis.library.digitalcontent.config.PromotionProperties
import com.mundiapolis.library.digitalcontent.service.AssetPromotionService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
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

data class AssetPromotionHealthSnapshot(
    val ready: Boolean,
    val starting: Boolean,
    val consecutiveFailures: Int,
    val lastSuccessfulCycle: Instant?,
)

class AssetPromotionWorker(
    private val service: AssetPromotionService,
    private val properties: PromotionProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private val startedAt = AtomicReference<Instant>()
    private val lastSuccessfulCycle = AtomicReference<Instant>()
    private val consecutiveFailures = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("asset-promotion-worker").factory())
    private val claimed = counter(meterRegistry, "claimed")
    private val idle = counter(meterRegistry, "idle")
    private val failures = counter(meterRegistry, "failed")
    private var worker: Future<*>? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt.set(clock.instant())
        worker = executor.submit(::runUntilStopped)
    }

    override fun stop() {
        running.set(false)
        runCatching { worker?.get(STOP_TIMEOUT.seconds, TimeUnit.SECONDS) }
        executor.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()
    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = -1

    fun healthSnapshot(now: Instant): AssetPromotionHealthSnapshot {
        val started = startedAt.get()
        val lastCycle = lastSuccessfulCycle.get()
        val starting = started != null && lastCycle == null &&
            Duration.between(started, now) <= properties.startupGracePeriod
        val stale = lastCycle == null || Duration.between(lastCycle, now) > properties.maximumPollSilence
        val failureCount = consecutiveFailures.get()
        return AssetPromotionHealthSnapshot(
            ready = running.get() && !stale && failureCount < properties.maximumConsecutiveFailures,
            starting = starting,
            consecutiveFailures = failureCount,
            lastSuccessfulCycle = lastCycle,
        )
    }

    internal fun runCycle(): Boolean {
        return try {
            val hadWork = service.runOnce()
            lastSuccessfulCycle.set(clock.instant())
            consecutiveFailures.set(0)
            if (hadWork) claimed.increment() else idle.increment()
            hadWork
        } catch (_: Exception) {
            failures.increment()
            consecutiveFailures.incrementAndGet()
            logger.error("Asset promotion cycle failed")
            false
        }
    }

    private fun runUntilStopped() {
        while (running.get()) {
            val hadWork = runCycle()
            if (!hadWork) sleep(properties.pollInterval)
        }
    }

    private fun sleep(duration: Duration) {
        try {
            Thread.sleep(duration)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running.set(false)
        }
    }

    private fun counter(registry: MeterRegistry, outcome: String): Counter = Counter.builder(METRIC)
        .tag("outcome", outcome)
        .register(registry)

    private companion object {
        val logger = LoggerFactory.getLogger(AssetPromotionWorker::class.java)
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val METRIC = "mundia_digital_content_asset_promotion_cycles_total"
    }
}
