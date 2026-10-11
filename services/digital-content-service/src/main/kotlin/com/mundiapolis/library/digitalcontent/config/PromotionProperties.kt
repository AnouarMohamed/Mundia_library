package com.mundiapolis.library.digitalcontent.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.promotion")
class PromotionProperties(
    val enabled: Boolean,
    val pollInterval: Duration,
    val leaseDuration: Duration,
    val maximumAttempts: Int,
    val initialBackoff: Duration,
    val maximumBackoff: Duration,
    val maximumPollSilence: Duration,
    val startupGracePeriod: Duration,
    val maximumConsecutiveFailures: Int,
) {
    @get:AssertTrue(message = "enabled promotion configuration is unsafe or incomplete")
    val isSafeConfiguration: Boolean
        get() = !enabled || (
            pollInterval in Duration.ofMillis(100)..Duration.ofSeconds(30) &&
                leaseDuration in Duration.ofSeconds(30)..Duration.ofMinutes(15) &&
                maximumAttempts in 1..10 &&
                initialBackoff in Duration.ofSeconds(1)..Duration.ofMinutes(5) &&
                maximumBackoff in initialBackoff..Duration.ofHours(1) &&
                maximumPollSilence in pollInterval.multipliedBy(2)..Duration.ofMinutes(10) &&
                startupGracePeriod in Duration.ofSeconds(10)..Duration.ofMinutes(5) &&
                maximumConsecutiveFailures in 1..20
            )
}
