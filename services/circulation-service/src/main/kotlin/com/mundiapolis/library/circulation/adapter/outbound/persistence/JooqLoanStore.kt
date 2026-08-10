package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationLoanRecord
import com.mundiapolis.library.circulation.application.port.outbound.LoanStore
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqLoanStore(
    private val dsl: DSLContext,
) : LoanStore {
    override fun create(loan: Loan, now: Instant): Boolean =
        dsl.insertInto(CIRCULATION_LOAN)
            .set(CIRCULATION_LOAN.ID, loan.id.value)
            .set(CIRCULATION_LOAN.MEMBER_ID, loan.memberId.value)
            .set(CIRCULATION_LOAN.EDITION_ID, loan.editionId.value)
            .set(CIRCULATION_LOAN.COPY_ID, loan.copyId?.value)
            .set(CIRCULATION_LOAN.STATUS, loan.status.name)
            .set(CIRCULATION_LOAN.REQUESTED_AT, loan.requestedAt.toOffsetDateTime())
            .set(CIRCULATION_LOAN.CHECKED_OUT_AT, loan.checkedOutAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.DUE_AT, loan.dueAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.RETURNED_AT, loan.returnedAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.REJECTED_AT, loan.rejectedAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.RENEWAL_COUNT, loan.renewalCount)
            .set(CIRCULATION_LOAN.VERSION, loan.version)
            .set(CIRCULATION_LOAN.CREATED_AT, now.toOffsetDateTime())
            .set(CIRCULATION_LOAN.UPDATED_AT, now.toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1

    override fun lockById(id: LoanId): Loan? =
        dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(id.value))
            .forUpdate()
            .fetchOne()
            ?.toDomain()

    override fun update(loan: Loan, expectedVersion: Long, now: Instant): Boolean =
        dsl.update(CIRCULATION_LOAN)
            .set(CIRCULATION_LOAN.COPY_ID, loan.copyId?.value)
            .set(CIRCULATION_LOAN.STATUS, loan.status.name)
            .set(CIRCULATION_LOAN.CHECKED_OUT_AT, loan.checkedOutAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.DUE_AT, loan.dueAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.RETURNED_AT, loan.returnedAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.REJECTED_AT, loan.rejectedAt?.toOffsetDateTime())
            .set(CIRCULATION_LOAN.RENEWAL_COUNT, loan.renewalCount)
            .set(CIRCULATION_LOAN.VERSION, loan.version)
            .set(CIRCULATION_LOAN.UPDATED_AT, now.toOffsetDateTime())
            .where(
                CIRCULATION_LOAN.ID.eq(loan.id.value)
                    .and(CIRCULATION_LOAN.VERSION.eq(expectedVersion)),
            )
            .execute() == 1

    override fun hasOpenForMemberEdition(memberId: MemberId, editionId: EditionId): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(CIRCULATION_LOAN)
                .where(
                    CIRCULATION_LOAN.MEMBER_ID.eq(memberId.value)
                        .and(CIRCULATION_LOAN.EDITION_ID.eq(editionId.value))
                        .and(CIRCULATION_LOAN.STATUS.`in`("REQUESTED", "ACTIVE")),
                ),
        )

    private fun CirculationLoanRecord.toDomain(): Loan = Loan.restore(
        id = LoanId(requireNotNull(id)),
        memberId = MemberId(requireNotNull(memberId)),
        editionId = EditionId(requireNotNull(editionId)),
        copyId = copyId?.let(::CopyId),
        status = LoanStatus.valueOf(requireNotNull(status)),
        requestedAt = requireNotNull(requestedAt).toInstant(),
        checkedOutAt = checkedOutAt?.toInstant(),
        dueAt = dueAt?.toInstant(),
        returnedAt = returnedAt?.toInstant(),
        rejectedAt = rejectedAt?.toInstant(),
        renewalCount = requireNotNull(renewalCount),
        version = requireNotNull(version),
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
