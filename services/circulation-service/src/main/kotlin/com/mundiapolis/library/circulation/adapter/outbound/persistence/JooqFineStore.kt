package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationFineRecord
import com.mundiapolis.library.circulation.application.port.outbound.FineStore
import com.mundiapolis.library.circulation.domain.model.Fine
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.FineStatus
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqFineStore(
    private val dsl: DSLContext,
) : FineStore {
    override fun create(fine: Fine, now: Instant): Boolean =
        dsl.insertInto(CIRCULATION_FINE)
            .set(CIRCULATION_FINE.ID, fine.id.value)
            .set(CIRCULATION_FINE.LOAN_ID, fine.loanId.value)
            .set(CIRCULATION_FINE.MEMBER_ID, fine.memberId.value)
            .set(CIRCULATION_FINE.CURRENCY, fine.currency)
            .set(CIRCULATION_FINE.BALANCE_MINOR, fine.balanceMinor)
            .set(CIRCULATION_FINE.STATUS, fine.status.name)
            .set(CIRCULATION_FINE.VERSION, fine.version)
            .set(CIRCULATION_FINE.CREATED_AT, fine.createdAt.toOffsetDateTime())
            .set(CIRCULATION_FINE.UPDATED_AT, now.toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1

    override fun lockById(id: FineId): Fine? =
        dsl.selectFrom(CIRCULATION_FINE)
            .where(CIRCULATION_FINE.ID.eq(id.value))
            .forUpdate()
            .fetchOne()
            ?.toDomain()

    override fun update(fine: Fine, expectedVersion: Long, now: Instant): Boolean =
        dsl.update(CIRCULATION_FINE)
            .set(CIRCULATION_FINE.BALANCE_MINOR, fine.balanceMinor)
            .set(CIRCULATION_FINE.STATUS, fine.status.name)
            .set(CIRCULATION_FINE.VERSION, fine.version)
            .set(CIRCULATION_FINE.UPDATED_AT, now.toOffsetDateTime())
            .where(
                CIRCULATION_FINE.ID.eq(fine.id.value)
                    .and(CIRCULATION_FINE.VERSION.eq(expectedVersion)),
            )
            .execute() == 1

    private fun CirculationFineRecord.toDomain(): Fine = Fine.restore(
        id = FineId(requireNotNull(id)),
        loanId = LoanId(requireNotNull(loanId)),
        memberId = MemberId(requireNotNull(memberId)),
        currency = requireNotNull(currency),
        balanceMinor = requireNotNull(balanceMinor),
        status = FineStatus.valueOf(requireNotNull(status)),
        version = requireNotNull(version),
        createdAt = requireNotNull(createdAt).toInstant(),
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
