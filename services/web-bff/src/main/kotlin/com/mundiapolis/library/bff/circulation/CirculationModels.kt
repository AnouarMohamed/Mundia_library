package com.mundiapolis.library.bff.circulation

import java.time.Instant
import java.util.UUID

data class CirculationEligibilityView(
    val memberId: UUID,
    val status: EligibilityStatusView,
    val reasonCode: String?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
)

enum class EligibilityStatusView {
    ELIGIBLE,
    INELIGIBLE,
    SUSPENDED,
}

data class RequestLoanView(val editionId: UUID)

internal data class DownstreamLoanRequest(val editionId: UUID)

data class LoanCommandView(
    val loanId: UUID,
    val memberId: UUID,
    val editionId: UUID,
    val copyId: UUID?,
    val status: LoanStatusView,
    val requestedAt: Instant,
    val checkedOutAt: Instant?,
    val dueAt: Instant?,
    val returnedAt: Instant?,
    val renewalCount: Int,
    val version: Long,
)

enum class LoanStatusView {
    REQUESTED,
    ACTIVE,
    RETURNED,
    REJECTED,
    CANCELLED,
}

data class LoanRequestResult(
    val loan: LoanCommandView,
    val idempotencyReplayed: Boolean,
)
