package com.mundiapolis.library.circulation.domain.model

import java.time.Instant
import java.util.Currency

enum class FineStatus {
    OPEN,
    SETTLED,
}

enum class FineLedgerEntryType {
    ASSESSMENT,
    PAYMENT,
    ADJUSTMENT,
}

data class Fine private constructor(
    val id: FineId,
    val loanId: LoanId,
    val memberId: MemberId,
    val currency: String,
    val balanceMinor: Long,
    val status: FineStatus,
    val version: Long,
    val createdAt: Instant,
) {
    init {
        require(isSupportedCurrency(currency)) {
            "Currency must be an ISO 4217 currency with minor units"
        }
        require(balanceMinor in 0..MAX_AMOUNT_MINOR) { "Fine balance is outside the supported range" }
        require(version >= 0) { "Fine version cannot be negative" }
        require(
            (status == FineStatus.OPEN && balanceMinor > 0) ||
                (status == FineStatus.SETTLED && balanceMinor == 0L),
        ) { "Fine status does not match its balance" }
    }

    fun recordPayment(amountMinor: Long, currency: String): Fine {
        require(currency == this.currency) { "Payment currency does not match the fine currency" }
        require(amountMinor in 1..MAX_AMOUNT_MINOR) { "Payment amount is outside the supported range" }
        require(amountMinor <= balanceMinor) { "Payment exceeds the outstanding fine balance" }
        return withBalance(balanceMinor - amountMinor)
    }

    fun adjust(deltaMinor: Long, currency: String): Fine {
        require(currency == this.currency) { "Adjustment currency does not match the fine currency" }
        require(deltaMinor != 0L && deltaMinor in -MAX_AMOUNT_MINOR..MAX_AMOUNT_MINOR) {
            "Adjustment amount is outside the supported range"
        }
        val adjusted = try {
            Math.addExact(balanceMinor, deltaMinor)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Adjustment would overflow the supported balance")
        }
        require(adjusted in 0..MAX_AMOUNT_MINOR) {
            "Adjustment would produce an unsupported fine balance"
        }
        return withBalance(adjusted)
    }

    private fun withBalance(newBalanceMinor: Long): Fine = copy(
        balanceMinor = newBalanceMinor,
        status = if (newBalanceMinor == 0L) FineStatus.SETTLED else FineStatus.OPEN,
        version = version + 1,
    )

    companion object {
        const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
        private val CURRENCY_PATTERN = Regex("[A-Z]{3}")

        internal fun isSupportedCurrency(code: String): Boolean =
            CURRENCY_PATTERN.matches(code) &&
                (
                    runCatching { Currency.getInstance(code) }
                        .getOrNull()
                        ?.defaultFractionDigits
                        ?.let { it >= 0 }
                        ?: false
                )

        fun assess(
            id: FineId,
            loanId: LoanId,
            memberId: MemberId,
            currency: String,
            amountMinor: Long,
            assessedAt: Instant,
        ): Fine {
            require(amountMinor in 1..MAX_AMOUNT_MINOR) {
                "Fine amount is outside the supported range"
            }
            return Fine(
                id = id,
                loanId = loanId,
                memberId = memberId,
                currency = currency,
                balanceMinor = amountMinor,
                status = FineStatus.OPEN,
                version = 0,
                createdAt = assessedAt,
            )
        }

        fun restore(
            id: FineId,
            loanId: LoanId,
            memberId: MemberId,
            currency: String,
            balanceMinor: Long,
            status: FineStatus,
            version: Long,
            createdAt: Instant,
        ): Fine = Fine(
            id = id,
            loanId = loanId,
            memberId = memberId,
            currency = currency,
            balanceMinor = balanceMinor,
            status = status,
            version = version,
            createdAt = createdAt,
        )
    }
}

data class FineLedgerEntry(
    val id: FineLedgerEntryId,
    val fineId: FineId,
    val fineVersion: Long,
    val type: FineLedgerEntryType,
    val deltaMinor: Long,
    val actorFingerprint: String,
    val reason: String?,
    val externalReference: String?,
    val occurredAt: Instant,
) {
    init {
        require(fineVersion >= 0) { "Fine version cannot be negative" }
        require(deltaMinor != 0L && deltaMinor in -Fine.MAX_AMOUNT_MINOR..Fine.MAX_AMOUNT_MINOR) {
            "Ledger delta is outside the supported range"
        }
        require(ACTOR_FINGERPRINT_PATTERN.matches(actorFingerprint)) {
            "Ledger actor fingerprint is invalid"
        }
        when (type) {
            FineLedgerEntryType.ASSESSMENT -> {
                require(fineVersion == 0L && deltaMinor > 0)
                requireNarrative(reason)
                require(externalReference == null)
            }

            FineLedgerEntryType.PAYMENT -> {
                require(fineVersion > 0 && deltaMinor < 0)
                require(reason == null)
                requireReference(externalReference)
            }

            FineLedgerEntryType.ADJUSTMENT -> {
                require(fineVersion > 0)
                requireNarrative(reason)
                require(externalReference == null)
            }
        }
    }

    companion object {
        private val ACTOR_FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")

        private fun requireNarrative(value: String?) {
            require(value != null && value.length in 1..500 && value.isNotBlank()) {
                "Ledger reason must contain 1 to 500 characters"
            }
            require(value.none(Char::isISOControl)) { "Ledger reason contains control characters" }
        }

        private fun requireReference(value: String?) {
            require(value != null && value.length in 8..128 && value.isNotBlank()) {
                "Payment reference must contain 8 to 128 characters"
            }
            require(value.all { it.code in 0x21..0x7e }) {
                "Payment reference must contain visible ASCII characters"
            }
        }
    }
}
