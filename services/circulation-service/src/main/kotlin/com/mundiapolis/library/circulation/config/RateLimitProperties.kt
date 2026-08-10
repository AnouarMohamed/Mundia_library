package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean,
    val window: Duration,
    val readRequests: Int,
    val commandRequests: Int,
    val sensitiveRequests: Int,
    val cleanupInterval: Duration,
    val cleanupBatchSize: Int,
) {
    @get:AssertTrue(message = "rate-limit window must be between one second and one hour")
    val isWindowValid: Boolean
        get() = !window.isZero && !window.isNegative && window <= Duration.ofHours(1)

    @get:AssertTrue(message = "rate-limit budgets must be between 1 and 1000000")
    val areBudgetsValid: Boolean
        get() = listOf(readRequests, commandRequests, sensitiveRequests).all { it in 1..1_000_000 }

    @get:AssertTrue(message = "rate-limit cleanup interval must be between one minute and one day")
    val isCleanupIntervalValid: Boolean
        get() = cleanupInterval in Duration.ofMinutes(1)..Duration.ofDays(1)

    @get:AssertTrue(message = "rate-limit cleanup batch size must be between 100 and 100000")
    val isCleanupBatchSizeValid: Boolean
        get() = cleanupBatchSize in 100..100_000
}
