package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.loan-reminder")
data class LoanReminderProperties(
    val enabled: Boolean,
    val pollInterval: Duration,
    val dueSoonLeadTime: Duration,
    val batchSize: Int,
) {
    @get:AssertTrue(message = "loan reminder poll interval must be between ten seconds and one hour")
    val isPollIntervalValid: Boolean
        get() = pollInterval in Duration.ofSeconds(10)..Duration.ofHours(1)

    @get:AssertTrue(message = "loan reminder due-soon lead time must be between one hour and thirty days")
    val isDueSoonLeadTimeValid: Boolean
        get() = dueSoonLeadTime in Duration.ofHours(1)..Duration.ofDays(30)

    @get:AssertTrue(message = "loan reminder batch size must be between 1 and 1000")
    val isBatchSizeValid: Boolean
        get() = batchSize in 1..1000
}
