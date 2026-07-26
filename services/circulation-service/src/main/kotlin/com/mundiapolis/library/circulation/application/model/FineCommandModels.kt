package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.Fine
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntry
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryType
import com.mundiapolis.library.circulation.domain.model.FineStatus
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.MemberId
import java.time.Instant
import java.util.UUID

@JvmInline
value class FineNarrative private constructor(val value: String) {
    companion object {
        fun parse(raw: String): FineNarrative {
            if (
                raw.length !in 1..500 ||
                raw.isBlank() ||
                raw != raw.trim() ||
                raw.any(Char::isISOControl)
            ) {
                throw InvalidFineNarrativeException()
            }
            return FineNarrative(raw)
        }
    }
}

@JvmInline
value class PaymentReference private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PaymentReference {
            if (raw.length !in 8..128 || raw.any { it.code !in 0x21..0x7e }) {
                throw InvalidPaymentReferenceException()
            }
            return PaymentReference(raw)
        }
    }
}

data class FineCommandResult(
    val fineId: FineId,
    val loanId: LoanId,
    val memberId: MemberId,
    val currency: String,
    val balanceMinor: Long,
    val status: FineStatus,
    val version: Long,
    val ledgerEntryId: FineLedgerEntryId,
    val ledgerEntryType: FineLedgerEntryType,
    val ledgerDeltaMinor: Long,
    val occurredAt: Instant,
) {
    companion object {
        fun from(fine: Fine, entry: FineLedgerEntry): FineCommandResult = FineCommandResult(
            fineId = fine.id,
            loanId = fine.loanId,
            memberId = fine.memberId,
            currency = fine.currency,
            balanceMinor = fine.balanceMinor,
            status = fine.status,
            version = fine.version,
            ledgerEntryId = entry.id,
            ledgerEntryType = entry.type,
            ledgerDeltaMinor = entry.deltaMinor,
            occurredAt = entry.occurredAt,
        )
    }
}

data class FineCommandExecution(
    val result: FineCommandResult,
    val replayed: Boolean,
)

data class StoredFineIdempotencyResult(
    val operation: CommandOperation,
    val requestFingerprint: String,
    val result: FineCommandResult?,
)

data class FineOutboxEvent(
    val id: UUID,
    val aggregateId: FineId,
    val aggregateVersion: Long,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val result: FineCommandResult,
    val actorFingerprint: String,
    val externalReference: String?,
)

class InvalidFineAmountException :
    CirculationCommandException("Fine amount must be within the supported positive minor-unit range")

class InvalidFineAdjustmentException :
    CirculationCommandException("Fine adjustment must be a supported non-zero minor-unit amount")

class InvalidFineNarrativeException :
    CirculationCommandException(
        "Fine reason must be trimmed, contain 1 to 500 characters, and have no controls",
    )

class InvalidPaymentReferenceException :
    CirculationCommandException(
        "Payment reference must contain 8 to 128 visible ASCII characters",
    )

class FineNotFoundException(id: FineId) :
    CirculationCommandException("Fine ${id.value} was not found")

class FineBalanceConflictException :
    CirculationCommandException("The command would make the fine balance invalid")

class DuplicatePaymentReferenceException :
    CirculationCommandException("The payment reference has already been recorded")

class LoanNotEligibleForFineException(id: LoanId) :
    CirculationCommandException("Loan ${id.value} is not eligible for a fine assessment")

class FinePersistenceConflictException :
    CirculationCommandException("The fine state changed concurrently; retry the command")
