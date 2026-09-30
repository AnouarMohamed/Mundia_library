package com.mundiapolis.library.notification.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.email-worker.ses")
class AwsSesProperties(
    val enabled: Boolean,
    val region: String,
    val fromAddress: String,
    val configurationSetName: String,
    val callTimeout: Duration,
    val attemptTimeout: Duration,
) {
    @get:AssertTrue(message = "enabled AWS SES configuration is unsafe or incomplete")
    val isSafeConfiguration: Boolean
        get() = !enabled || (
            REGION.matches(region) && EMAIL.matches(fromAddress) && fromAddress.all { it.code <= 127 } &&
                fromAddress != DISABLED_FROM_ADDRESS &&
                CONFIGURATION_SET.matches(configurationSetName) && configurationSetName != DISABLED_CONFIGURATION_SET &&
                callTimeout in Duration.ofMillis(500)..Duration.ofSeconds(30) &&
                attemptTimeout in Duration.ofMillis(250)..callTimeout
            )

    private companion object {
        val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z]+-[1-9][0-9]?")
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
        val CONFIGURATION_SET = Regex("[A-Za-z0-9_-]{1,64}")
        const val DISABLED_FROM_ADDRESS = "disabled@example.invalid"
        const val DISABLED_CONFIGURATION_SET = "disabled"
    }
}
