package com.mundiapolis.library.circulation.config

import com.mundiapolis.library.circulation.application.service.ReservationExpiryService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.reservation-expiry", name = ["enabled"], havingValue = "true")
class ReservationExpiryConfiguration {
    @Bean
    fun reservationExpiryScheduler(
        service: ReservationExpiryService,
        properties: ReservationExpiryProperties,
        meterRegistry: MeterRegistry,
    ): ReservationExpiryScheduler = ReservationExpiryScheduler(service, properties, meterRegistry)
}

class ReservationExpiryScheduler(
    private val service: ReservationExpiryService,
    private val properties: ReservationExpiryProperties,
    meterRegistry: MeterRegistry,
) {
    private val expiredCounter = meterRegistry.counter("mundia.reservation.expiry.completed")
    private val failureCounter = meterRegistry.counter("mundia.reservation.expiry.failed")

    @Scheduled(fixedDelayString = "\${app.reservation-expiry.poll-interval}")
    fun expireDueReservations() {
        service.findDue(properties.batchSize).forEach { id ->
            try {
                if (service.expireIfDue(id)) expiredCounter.increment()
            } catch (exception: RuntimeException) {
                failureCounter.increment()
                LOGGER.error("Reservation expiry failed for {}", id.value, exception)
            }
        }
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(ReservationExpiryScheduler::class.java)
    }
}
