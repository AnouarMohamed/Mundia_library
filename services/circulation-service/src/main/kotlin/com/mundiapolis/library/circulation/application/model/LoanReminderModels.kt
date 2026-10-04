package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.LoanId
import java.time.Instant
import java.util.UUID

enum class LoanReminderType {
    DUE_SOON,
    OVERDUE,
}

data class LoanReminderCandidate(
    val loanId: LoanId,
    val dueAt: Instant,
    val type: LoanReminderType,
)

data class LoanReminderReceipt(
    val loanId: LoanId,
    val dueAt: Instant,
    val type: LoanReminderType,
    val notificationEventId: UUID,
    val createdAt: Instant,
)
