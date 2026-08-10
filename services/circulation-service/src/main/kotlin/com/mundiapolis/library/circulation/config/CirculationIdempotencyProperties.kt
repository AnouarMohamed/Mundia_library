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
        get() = !idempotencyRetention.isZero &&
            !idempotencyRetention.isNegative &&
            idempotencyRetention <= MAXIMUM_RETENTION

    private companion object {
        val MAXIMUM_RETENTION: Duration = Duration.ofDays(365)
    }
}
