package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN_NOTIFICATION_REMINDER
import com.mundiapolis.library.circulation.application.model.LoanReminderCandidate
import com.mundiapolis.library.circulation.application.model.LoanReminderReceipt
import com.mundiapolis.library.circulation.application.model.LoanReminderType
import com.mundiapolis.library.circulation.application.port.outbound.LoanReminderStore
import com.mundiapolis.library.circulation.domain.model.LoanId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqLoanReminderStore(
    private val dsl: DSLContext,
) : LoanReminderStore {
    override fun lockCandidates(
        type: LoanReminderType,
        now: Instant,
        dueSoonCutoff: Instant,
        batchSize: Int,
    ): List<LoanReminderCandidate> {
        val reminder = CIRCULATION_LOAN_NOTIFICATION_REMINDER.`as`("reminder")
        val nowUtc = now.toOffsetDateTime()
        val eligibility = when (type) {
            LoanReminderType.DUE_SOON ->
                CIRCULATION_LOAN.DUE_AT.gt(nowUtc)
                    .and(CIRCULATION_LOAN.DUE_AT.le(dueSoonCutoff.toOffsetDateTime()))
            LoanReminderType.OVERDUE -> CIRCULATION_LOAN.DUE_AT.le(nowUtc)
        }
        return dsl.select(CIRCULATION_LOAN.ID, CIRCULATION_LOAN.DUE_AT)
            .from(CIRCULATION_LOAN)
            .where(
                CIRCULATION_LOAN.STATUS.eq("ACTIVE")
                    .and(CIRCULATION_LOAN.DUE_AT.isNotNull)
                    .and(eligibility)
                    .andNotExists(
                        DSL.selectOne()
                            .from(reminder)
                            .where(
                                reminder.LOAN_ID.eq(CIRCULATION_LOAN.ID)
                                    .and(reminder.DUE_AT.eq(CIRCULATION_LOAN.DUE_AT))
                                    .and(reminder.REMINDER_TYPE.eq(type.name)),
                            ),
                    ),
            )
            .orderBy(CIRCULATION_LOAN.DUE_AT.asc(), CIRCULATION_LOAN.ID.asc())
            .limit(batchSize)
            .forUpdate()
            .skipLocked()
            .fetch { record ->
                LoanReminderCandidate(
                    loanId = LoanId(requireNotNull(record[CIRCULATION_LOAN.ID])),
                    dueAt = requireNotNull(record[CIRCULATION_LOAN.DUE_AT]).toInstant(),
                    type = type,
                )
            }
    }

    override fun claim(receipt: LoanReminderReceipt): Boolean =
        dsl.insertInto(CIRCULATION_LOAN_NOTIFICATION_REMINDER)
            .set(CIRCULATION_LOAN_NOTIFICATION_REMINDER.LOAN_ID, receipt.loanId.value)
            .set(CIRCULATION_LOAN_NOTIFICATION_REMINDER.DUE_AT, receipt.dueAt.toOffsetDateTime())
            .set(CIRCULATION_LOAN_NOTIFICATION_REMINDER.REMINDER_TYPE, receipt.type.name)
            .set(
                CIRCULATION_LOAN_NOTIFICATION_REMINDER.NOTIFICATION_EVENT_ID,
                receipt.notificationEventId,
            )
            .set(CIRCULATION_LOAN_NOTIFICATION_REMINDER.CREATED_AT, receipt.createdAt.toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
