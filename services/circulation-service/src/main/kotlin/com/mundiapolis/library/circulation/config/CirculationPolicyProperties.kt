package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration
import java.util.Currency

@Validated
@ConfigurationProperties("app.circulation")
data class CirculationPolicyProperties(
    val defaultLoanPeriod: Duration,
    val renewalPeriod: Duration,
    val maximumRenewals: Int,
    val fineCurrency: String,
    val idempotencyRetention: Duration,
) {
    @get:AssertTrue(message = "default loan period must be between one second and 365 days")
    val isDefaultLoanPeriodValid: Boolean
        get() = defaultLoanPeriod.isBoundedPositiveDuration()

    @get:AssertTrue(message = "renewal period must be between one second and 365 days")
    val isRenewalPeriodValid: Boolean
        get() = renewalPeriod.isBoundedPositiveDuration()

    @get:AssertTrue(message = "maximum renewals must be between 1 and 100")
    val isMaximumRenewalsValid: Boolean
        get() = maximumRenewals in 1..100

    @get:AssertTrue(message = "fine currency must be an ISO 4217 currency with minor units")
    val isFineCurrencyValid: Boolean
        get() {
            if (!Regex("[A-Z]{3}").matches(fineCurrency)) {
                return false
            }
            return runCatching { Currency.getInstance(fineCurrency) }
                .getOrNull()
                ?.defaultFractionDigits
                ?.let { it >= 0 }
                ?: false
        }

    @get:AssertTrue(message = "idempotency retention must be between one second and 365 days")
    val isIdempotencyRetentionValid: Boolean
        get() = idempotencyRetention.isBoundedPositiveDuration()

    private fun Duration.isBoundedPositiveDuration(): Boolean =
        !isZero && !isNegative && this <= MAX_POLICY_DURATION

    private companion object {
        val MAX_POLICY_DURATION: Duration = Duration.ofDays(365)
    }
}
