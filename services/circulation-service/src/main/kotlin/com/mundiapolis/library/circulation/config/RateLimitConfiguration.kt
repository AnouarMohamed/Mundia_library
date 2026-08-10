package com.mundiapolis.library.circulation.config

import com.mundiapolis.library.circulation.application.port.outbound.RateLimitMaintenanceStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.rate-limit", name = ["enabled"], havingValue = "true")
class RateLimitConfiguration(
    private val maintenanceStore: RateLimitMaintenanceStore,
    private val timeProvider: TimeProvider,
    private val properties: RateLimitProperties,
) {
    @Scheduled(fixedDelayString = "\${app.rate-limit.cleanup-interval}")
    fun cleanupExpiredBuckets() {
        try {
            maintenanceStore.deleteExpired(timeProvider.now(), properties.cleanupBatchSize)
        } catch (exception: RuntimeException) {
            LOGGER.warn("Rate-limit bucket cleanup failed", exception)
        }
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(RateLimitConfiguration::class.java)
    }
}
