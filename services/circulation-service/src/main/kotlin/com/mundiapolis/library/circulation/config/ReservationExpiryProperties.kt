package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.reservation-expiry")
data class ReservationExpiryProperties(
    val enabled: Boolean,
    val pollInterval: Duration,
    val batchSize: Int,
) {
    @get:AssertTrue(message = "reservation expiry poll interval must be between one second and one hour")
    val isPollIntervalValid: Boolean
        get() = pollInterval in Duration.ofSeconds(1)..Duration.ofHours(1)

    @get:AssertTrue(message = "reservation expiry batch size must be between 1 and 1000")
    val isBatchSizeValid: Boolean
        get() = batchSize in 1..1000
}
