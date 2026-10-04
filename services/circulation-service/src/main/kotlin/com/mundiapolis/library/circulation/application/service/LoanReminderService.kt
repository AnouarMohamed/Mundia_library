package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.LoanReminderCandidate
import com.mundiapolis.library.circulation.application.model.LoanReminderReceipt
import com.mundiapolis.library.circulation.application.model.LoanReminderType
import com.mundiapolis.library.circulation.application.model.NotificationIntentOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.LoanReminderStore
import com.mundiapolis.library.circulation.application.port.outbound.LoanStore
import com.mundiapolis.library.circulation.application.port.outbound.NotificationIntentOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class LoanReminderService(
    private val transactionRunner: TransactionRunner,
    private val loanStore: LoanStore,
    private val reminderStore: LoanReminderStore,
    private val notificationOutboxEventStore: NotificationIntentOutboxEventStore,
    private val timeProvider: TimeProvider,
    private val identifierGenerator: IdentifierGenerator,
) {
    fun emitEligible(
        type: LoanReminderType,
        dueSoonLeadTime: Duration,
        batchSize: Int,
    ): Int = transactionRunner.required {
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        reminderStore.lockCandidates(type, now, now.plus(dueSoonLeadTime), batchSize)
            .count { candidate -> emitIfEligible(candidate, now, dueSoonLeadTime) }
    }

    private fun emitIfEligible(
        candidate: LoanReminderCandidate,
        now: Instant,
        dueSoonLeadTime: Duration,
    ): Boolean {
        val loan = loanStore.lockById(candidate.loanId) ?: return false
        val dueAt = loan.dueAt
        if (
            loan.status != LoanStatus.ACTIVE ||
            dueAt == null ||
            dueAt != candidate.dueAt ||
            !candidate.type.isEligible(dueAt, now, dueSoonLeadTime)
        ) {
            return false
        }

        val eventId = identifierGenerator.next()
        if (
            !reminderStore.claim(
                LoanReminderReceipt(
                    loanId = loan.id,
                    dueAt = dueAt,
                    type = candidate.type,
                    notificationEventId = eventId,
                    createdAt = now,
                ),
            )
        ) {
            return false
        }

        notificationOutboxEventStore.append(
            NotificationIntentOutboxEvent(
                id = eventId,
                memberId = loan.memberId.value,
                sourceType = candidate.type.sourceType,
                category = candidate.type.name,
                subject = candidate.type.subject,
                body = candidate.type.body(dueAt),
                channels = setOf("IN_APP", "EMAIL"),
                occurredAt = now,
            ),
        )
        return true
    }

    private fun LoanReminderType.isEligible(
        dueAt: Instant,
        now: Instant,
        dueSoonLeadTime: Duration,
    ): Boolean = when (this) {
        LoanReminderType.DUE_SOON -> dueAt > now && dueAt <= now.plus(dueSoonLeadTime)
        LoanReminderType.OVERDUE -> dueAt <= now
    }

    private val LoanReminderType.sourceType: String
        get() = when (this) {
            LoanReminderType.DUE_SOON -> "circulation.loan.due-soon.v1"
            LoanReminderType.OVERDUE -> "circulation.loan.overdue.v1"
        }

    private val LoanReminderType.subject: String
        get() = when (this) {
            LoanReminderType.DUE_SOON -> "Library loan due soon"
            LoanReminderType.OVERDUE -> "Library loan overdue"
        }

    private fun LoanReminderType.body(dueAt: Instant): String {
        val deadline = DATE_FORMATTER.format(dueAt)
        return when (this) {
            LoanReminderType.DUE_SOON -> "Your library loan is due on $deadline."
            LoanReminderType.OVERDUE -> "Your library loan was due on $deadline. Please return it promptly."
        }
    }

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
    }
}
