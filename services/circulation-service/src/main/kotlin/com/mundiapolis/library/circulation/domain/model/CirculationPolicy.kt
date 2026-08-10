package com.mundiapolis.library.circulation.domain.model

import java.time.Duration
import java.time.Instant
import java.util.Currency
import java.util.UUID

data class CirculationPolicy(
    val revisionId: UUID,
    val sequence: Long,
    val defaultLoanPeriod: Duration,
    val renewalPeriod: Duration,
    val maximumRenewals: Int,
    val fineCurrency: String,
    val reservationHoldPeriod: Duration,
    val maximumActiveReservations: Int,
    val effectiveAt: Instant,
    val actorFingerprint: String,
) {
    init {
        require(sequence >= 0) { "Policy sequence cannot be negative" }
        require(defaultLoanPeriod.isPolicyDuration(365)) { "Default loan period is invalid" }
        require(renewalPeriod.isPolicyDuration(365)) { "Renewal period is invalid" }
        require(maximumRenewals in 1..100) { "Maximum renewals is invalid" }
        require(
            Regex("[A-Z]{3}").matches(fineCurrency) &&
                runCatching { Currency.getInstance(fineCurrency).defaultFractionDigits >= 0 }
                    .getOrDefault(false),
        ) { "Fine currency is invalid" }
        require(reservationHoldPeriod.isPolicyDuration(30)) { "Reservation hold period is invalid" }
        require(maximumActiveReservations in 1..100) {
            "Maximum active reservations is invalid"
        }
        require(Regex("[0-9a-f]{64}").matches(actorFingerprint)) {
            "Policy actor fingerprint is invalid"
        }
    }

    private fun Duration.isPolicyDuration(maximumDays: Long): Boolean =
        !isZero && !isNegative && nano == 0 && this <= Duration.ofDays(maximumDays)
}
