package com.mundiapolis.library.notification.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.email-worker")
data class EmailDeliveryProperties(
    val enabled: Boolean,
    val instanceId: String,
    val pollInterval: Duration,
    val leaseDuration: Duration,
    val batchSize: Int,
    val maximumAttempts: Int,
    val retryBaseDelay: Duration,
    val retryMaximumDelay: Duration,
    val operationTimeout: Duration,
    val maximumPendingAge: Duration,
) {
    @get:AssertTrue(message = "enabled email worker configuration is unsafe or inconsistent")
    val isSafeConfiguration: Boolean
        get() = !enabled || (
            INSTANCE_ID.matches(instanceId) &&
                pollInterval in Duration.ofMillis(100)..Duration.ofSeconds(30) &&
                leaseDuration in Duration.ofSeconds(10)..Duration.ofMinutes(15) &&
                batchSize in 1..100 && maximumAttempts in 1..100 &&
                retryBaseDelay in Duration.ofMillis(100)..Duration.ofHours(1) &&
                retryMaximumDelay in retryBaseDelay..Duration.ofHours(1) &&
                operationTimeout in Duration.ofMillis(100)..Duration.ofMinutes(2) &&
                maximumPendingAge in Duration.ofSeconds(10)..Duration.ofDays(1) &&
                leaseDuration >= operationTimeout.multipliedBy(batchSize.toLong()).plusSeconds(10)
            )

    private companion object {
        val INSTANCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
    }
}
