package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.circulation")
data class CirculationPolicyProperties(
    val defaultLoanPeriod: Duration,
    val renewalPeriod: Duration,
    val maximumRenewals: Int,
    val fineCurrency: String,
    val idempotencyRetention: Duration,
) {
    @get:AssertTrue(message = "default loan period must be positive")
    val isDefaultLoanPeriodPositive: Boolean
        get() = !defaultLoanPeriod.isZero && !defaultLoanPeriod.isNegative

    @get:AssertTrue(message = "renewal period must be positive")
    val isRenewalPeriodPositive: Boolean
        get() = !renewalPeriod.isZero && !renewalPeriod.isNegative

    @get:AssertTrue(message = "maximum renewals must be between 1 and 100")
    val isMaximumRenewalsValid: Boolean
        get() = maximumRenewals in 1..100

    @get:AssertTrue(message = "fine currency must be a three-letter uppercase ISO 4217 code")
    val isFineCurrencyValid: Boolean
        get() = Regex("[A-Z]{3}").matches(fineCurrency)

    @get:AssertTrue(message = "idempotency retention must be positive")
    val isIdempotencyRetentionPositive: Boolean
        get() = !idempotencyRetention.isZero && !idempotencyRetention.isNegative
}
