package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.circulation")
data class CirculationIdempotencyProperties(
    val idempotencyRetention: Duration,
) {
    @get:AssertTrue(message = "idempotency retention must be between one second and 365 days")
    val isIdempotencyRetentionValid: Boolean
        get() = idempotencyRetention >= MINIMUM_RETENTION &&
            idempotencyRetention <= MAXIMUM_RETENTION

    private companion object {
        val MINIMUM_RETENTION: Duration = Duration.ofSeconds(1)
        val MAXIMUM_RETENTION: Duration = Duration.ofDays(365)
    }
}
