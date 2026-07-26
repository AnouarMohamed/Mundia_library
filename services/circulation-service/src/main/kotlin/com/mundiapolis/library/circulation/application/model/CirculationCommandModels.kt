package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@JvmInline
value class IdempotencyKey private constructor(val value: String) {
    companion object {
        private const val MIN_LENGTH = 16
        private const val MAX_LENGTH = 128

        fun parse(raw: String): IdempotencyKey {
            if (raw.length !in MIN_LENGTH..MAX_LENGTH || raw.any { it.code !in 0x21..0x7e }) {
                throw InvalidIdempotencyKeyException()
            }
            return IdempotencyKey(raw)
        }
    }
}

@JvmInline
value class IdempotencyOwner private constructor(val fingerprint: String) {
    companion object {
        private const val MAX_IDENTITY_PART_LENGTH = 512

        fun fromIdentity(
            issuer: String,
            subject: String?,
            authorizedParty: String?,
            clientId: String?,
        ): IdempotencyOwner {
            val validatedIssuer = validatePart("iss", issuer)
            val validatedSubject = subject?.let { validatePart("sub", it) }
            val validatedAuthorizedParty = authorizedParty?.let { validatePart("azp", it) }
            val validatedClientId = clientId?.let { validatePart("client_id", it) }
            if (
                validatedSubject == null &&
                validatedAuthorizedParty == null &&
                validatedClientId == null
            ) {
                throw InvalidActorIdentityException()
            }

            val canonicalIdentity = buildString {
                appendPart("iss", validatedIssuer)
                appendPart("sub", validatedSubject)
                appendPart("azp", validatedAuthorizedParty)
                appendPart("client_id", validatedClientId)
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalIdentity.toByteArray(StandardCharsets.UTF_8))
            return IdempotencyOwner(HexFormat.of().formatHex(digest))
        }

        private fun validatePart(name: String, value: String): String {
            if (
                value.isBlank() ||
                value.length > MAX_IDENTITY_PART_LENGTH ||
                value.any(Char::isISOControl)
            ) {
                throw InvalidAuthenticationClaimException(name)
            }
            return value
        }

        private fun StringBuilder.appendPart(name: String, value: String?) {
            append(name)
            append(':')
            if (value == null) {
                append("-1:")
            } else {
                append(value.length)
                append(':')
                append(value)
            }
            append('\u001f')
        }
    }
}

data class CommandPrincipal(
    val idempotencyOwner: IdempotencyOwner,
    val membershipId: MemberId?,
    val canActOnBehalf: Boolean,
)

enum class CommandOperation(
    val responseStatus: Int,
    val eventType: String,
) {
    REQUEST_LOAN(201, "circulation.loan.requested"),
    APPROVE_LOAN(200, "circulation.loan.approved"),
    RENEW_LOAN(200, "circulation.loan.renewed"),
    RETURN_LOAN(200, "circulation.loan.returned"),
    ASSESS_FINE(201, "circulation.fine.assessed"),
    RECORD_FINE_PAYMENT(200, "circulation.fine.payment-recorded"),
    ADJUST_FINE(200, "circulation.fine.adjusted"),
    ;

    val isLoanOperation: Boolean
        get() = when (this) {
            REQUEST_LOAN,
            APPROVE_LOAN,
            RENEW_LOAN,
            RETURN_LOAN,
            -> true

            ASSESS_FINE,
            RECORD_FINE_PAYMENT,
            ADJUST_FINE,
            -> false
        }
}

data class LoanCommandResult(
    val loanId: LoanId,
    val memberId: MemberId,
    val editionId: EditionId,
    val copyId: CopyId?,
    val status: LoanStatus,
    val requestedAt: Instant,
    val checkedOutAt: Instant?,
    val dueAt: Instant?,
    val returnedAt: Instant?,
    val renewalCount: Int,
    val version: Long,
) {
    companion object {
        fun from(loan: Loan): LoanCommandResult = LoanCommandResult(
            loanId = loan.id,
            memberId = loan.memberId,
            editionId = loan.editionId,
            copyId = loan.copyId,
            status = loan.status,
            requestedAt = loan.requestedAt,
            checkedOutAt = loan.checkedOutAt,
            dueAt = loan.dueAt,
            returnedAt = loan.returnedAt,
            renewalCount = loan.renewalCount,
            version = loan.version,
        )
    }
}

data class CommandExecution(
    val result: LoanCommandResult,
    val replayed: Boolean,
)

data class StoredIdempotencyResult(
    val operation: CommandOperation,
    val requestFingerprint: String,
    val result: LoanCommandResult?,
)

data class CirculationOutboxEvent(
    val id: UUID,
    val aggregateId: LoanId,
    val aggregateVersion: Long,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val result: LoanCommandResult,
)

sealed class CirculationCommandException(message: String) : RuntimeException(message)

class InvalidIdempotencyKeyException :
    CirculationCommandException(
        "Idempotency-Key must contain 16 to 128 visible ASCII characters without whitespace",
    )

class InvalidActorIdentityException :
    CirculationCommandException("Authenticated token has no usable principal or client identity")

class InvalidAuthenticationClaimException(claim: String) :
    CirculationCommandException("Authenticated token contains an invalid $claim claim")

class MissingMembershipClaimException :
    CirculationCommandException(
        "Self-service circulation commands require a valid membership_id claim",
    )

class MemberAccessDeniedException :
    CirculationCommandException("The authenticated member cannot act for another member")

class IdempotencyKeyConflictException :
    CirculationCommandException("Idempotency-Key was already used for a different request")

class IncompleteIdempotencyRecordException :
    CirculationCommandException("Idempotency result is not available")

class LoanNotFoundException(id: LoanId) :
    CirculationCommandException("Loan ${id.value} was not found")

class OpenLoanAlreadyExistsException :
    CirculationCommandException("The member already has an open loan for this edition")

class LoanStateConflictException(id: LoanId, status: LoanStatus) :
    CirculationCommandException("Loan ${id.value} cannot be changed from state $status")

class LoanOverdueException(id: LoanId) :
    CirculationCommandException("Loan ${id.value} is overdue and cannot be renewed")

class RenewalLimitReachedException(id: LoanId) :
    CirculationCommandException("Loan ${id.value} has reached the maximum renewal count")

class NoAvailableCopyException(editionId: EditionId) :
    CirculationCommandException("No copy is currently available for edition ${editionId.value}")

class ConcurrentCirculationUpdateException :
    CirculationCommandException("The circulation state changed concurrently; retry the command")
