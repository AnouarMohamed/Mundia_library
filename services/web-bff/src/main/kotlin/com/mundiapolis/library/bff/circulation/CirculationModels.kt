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

data class LoanMutationResult(
    val loan: LoanCommandView,
    val idempotencyReplayed: Boolean,
)

data class MemberLoanPageView(
    val memberId: UUID,
    val items: List<LoanHistoryItemView>,
    val nextCursor: String?,
)

data class LoanHistoryItemView(
    val loanId: UUID,
    val memberId: UUID,
    val editionId: UUID,
    val copyId: UUID?,
    val status: LoanStatusView,
    val requestedAt: Instant,
    val checkedOutAt: Instant?,
    val dueAt: Instant?,
    val returnedAt: Instant?,
    val rejectedAt: Instant?,
    val renewalCount: Int,
    val version: Long,
)

data class RequestReservationView(val editionId: UUID)

internal data class DownstreamReservationRequest(val editionId: UUID)

data class ReservationCommandView(
    val reservationId: UUID,
    val memberId: UUID,
    val editionId: UUID,
    val copyId: UUID?,
    val status: ReservationStatusView,
    val placedAt: Instant,
    val readyAt: Instant?,
    val expiresAt: Instant?,
    val fulfilledAt: Instant?,
    val cancelledAt: Instant?,
    val version: Long,
)

enum class ReservationStatusView {
    WAITING,
    READY,
    FULFILLED,
    CANCELLED,
    EXPIRED,
}

data class ReservationCommandResult(
    val reservation: ReservationCommandView,
    val idempotencyReplayed: Boolean,
)

data class MemberReservationPageView(
    val memberId: UUID,
    val items: List<ReservationCommandView>,
    val nextCursor: String?,
)

data class AdministrativeCirculationOverviewView(
    val requestedLoans: Long,
    val activeLoans: Long,
    val overdueLoans: Long,
    val waitingReservations: Long,
    val readyReservations: Long,
)

data class AdministrativeLoanPageView(
    val items: List<LoanHistoryItemView>,
    val nextCursor: String?,
)

data class AdministrativeReservationPageView(
    val items: List<ReservationCommandView>,
    val nextCursor: String?,
)
