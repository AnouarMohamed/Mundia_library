package com.mundiapolis.library.circulation.domain.model

import java.time.Duration
import java.time.Instant

enum class LoanStatus {
    REQUESTED,
    ACTIVE,
    RETURNED,
    REJECTED,
    CANCELLED,
}

data class Loan private constructor(
    val id: LoanId,
    val memberId: MemberId,
    val editionId: EditionId,
    val copyId: CopyId?,
    val status: LoanStatus,
    val requestedAt: Instant,
    val checkedOutAt: Instant?,
    val dueAt: Instant?,
    val returnedAt: Instant?,
    val rejectedAt: Instant?,
    val renewalCount: Int,
    val version: Long,
) {
    init {
        require(version >= 0) { "Loan version cannot be negative" }
        require(renewalCount >= 0) { "Loan renewal count cannot be negative" }
        requireStateIsConsistent()
    }

    fun approve(copyId: CopyId, checkedOutAt: Instant, dueAt: Instant): Loan {
        require(status == LoanStatus.REQUESTED) { "Only requested loans can be approved" }
        require(dueAt > checkedOutAt) { "Due time must be after checkout time" }

        return copy(
            copyId = copyId,
            status = LoanStatus.ACTIVE,
            checkedOutAt = checkedOutAt,
            dueAt = dueAt,
            version = version + 1,
        )
    }

    fun returnAt(returnedAt: Instant): Loan {
        require(status == LoanStatus.ACTIVE) { "Only active loans can be returned" }
        require(returnedAt >= requireNotNull(checkedOutAt)) {
            "Return time cannot precede checkout time"
        }

        return copy(
            status = LoanStatus.RETURNED,
            returnedAt = returnedAt,
            version = version + 1,
        )
    }

    fun renew(renewedAt: Instant, renewalPeriod: Duration, maximumRenewals: Int): Loan {
        require(status == LoanStatus.ACTIVE) { "Only active loans can be renewed" }
        require(renewedAt <= requireNotNull(dueAt)) { "Overdue loans cannot be renewed" }
        require(!renewalPeriod.isZero && !renewalPeriod.isNegative) {
            "Renewal period must be positive"
        }
        require(maximumRenewals > 0) { "Maximum renewals must be positive" }
        require(renewalCount < maximumRenewals) { "Maximum renewal count reached" }

        return copy(
            dueAt = dueAt.plus(renewalPeriod),
            renewalCount = renewalCount + 1,
            version = version + 1,
        )
    }

    fun reject(rejectedAt: Instant): Loan {
        require(status == LoanStatus.REQUESTED) { "Only requested loans can be rejected" }

        return copy(
            status = LoanStatus.REJECTED,
            rejectedAt = rejectedAt,
            version = version + 1,
        )
    }

    fun cancel(): Loan {
        require(status == LoanStatus.REQUESTED) { "Only requested loans can be cancelled" }

        return copy(
            status = LoanStatus.CANCELLED,
            version = version + 1,
        )
    }

    private fun requireStateIsConsistent() {
        when (status) {
            LoanStatus.REQUESTED,
            LoanStatus.CANCELLED,
            -> {
                require(
                    copyId == null &&
                        checkedOutAt == null &&
                        dueAt == null &&
                        returnedAt == null &&
                        rejectedAt == null,
                )
            }

            LoanStatus.ACTIVE -> {
                require(
                    copyId != null &&
                        checkedOutAt != null &&
                        dueAt != null &&
                        returnedAt == null &&
                        rejectedAt == null,
                )
                require(dueAt > checkedOutAt)
            }

            LoanStatus.RETURNED -> {
                require(
                    copyId != null &&
                        checkedOutAt != null &&
                        dueAt != null &&
                        returnedAt != null &&
                        rejectedAt == null,
                )
                require(returnedAt >= checkedOutAt)
            }

            LoanStatus.REJECTED -> {
                require(copyId == null && checkedOutAt == null && dueAt == null && returnedAt == null)
                require(rejectedAt != null)
            }
        }
    }

    companion object {
        fun request(
            id: LoanId,
            memberId: MemberId,
            editionId: EditionId,
            requestedAt: Instant,
        ): Loan = Loan(
            id = id,
            memberId = memberId,
            editionId = editionId,
            copyId = null,
            status = LoanStatus.REQUESTED,
            requestedAt = requestedAt,
            checkedOutAt = null,
            dueAt = null,
            returnedAt = null,
            rejectedAt = null,
            renewalCount = 0,
            version = 0,
        )

        fun restore(
            id: LoanId,
            memberId: MemberId,
            editionId: EditionId,
            copyId: CopyId?,
            status: LoanStatus,
            requestedAt: Instant,
            checkedOutAt: Instant?,
            dueAt: Instant?,
            returnedAt: Instant?,
            rejectedAt: Instant?,
            renewalCount: Int,
            version: Long,
        ): Loan = Loan(
            id = id,
            memberId = memberId,
            editionId = editionId,
            copyId = copyId,
            status = status,
            requestedAt = requestedAt,
            checkedOutAt = checkedOutAt,
            dueAt = dueAt,
            returnedAt = returnedAt,
            rejectedAt = rejectedAt,
            renewalCount = renewalCount,
            version = version,
        )
    }
}
