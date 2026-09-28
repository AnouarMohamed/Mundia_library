package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_RESERVATION
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationLoanRecord
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationReservationRecord
import com.mundiapolis.library.circulation.application.port.outbound.CirculationHistoryReader
import com.mundiapolis.library.circulation.application.port.outbound.HistoryCursor
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.Reservation
import com.mundiapolis.library.circulation.domain.model.ReservationId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class JooqCirculationHistoryReader(private val dsl: DSLContext) : CirculationHistoryReader {
    override fun loans(
        memberId: MemberId,
        status: LoanStatus?,
        cursor: HistoryCursor?,
        limit: Int,
    ): List<Loan> {
        var condition = CIRCULATION_LOAN.MEMBER_ID.eq(memberId.value)
        if (status != null) condition = condition.and(CIRCULATION_LOAN.STATUS.eq(status.name))
        if (cursor != null) {
            val occurredAt = cursor.occurredAt.atOffset(java.time.ZoneOffset.UTC)
            condition = condition.and(
                CIRCULATION_LOAN.REQUESTED_AT.lt(occurredAt).or(
                    CIRCULATION_LOAN.REQUESTED_AT.eq(occurredAt)
                        .and(CIRCULATION_LOAN.ID.lt(cursor.aggregateId)),
                ),
            )
        }
        return dsl.selectFrom(CIRCULATION_LOAN)
            .where(condition)
            .orderBy(CIRCULATION_LOAN.REQUESTED_AT.desc(), CIRCULATION_LOAN.ID.desc())
            .limit(limit)
            .fetch()
            .map { it.toHistoryDomain() }
    }

    override fun reservations(
        memberId: MemberId,
        status: ReservationStatus?,
        cursor: HistoryCursor?,
        limit: Int,
    ): List<Reservation> {
        var condition = CIRCULATION_RESERVATION.MEMBER_ID.eq(memberId.value)
        if (status != null) condition = condition.and(CIRCULATION_RESERVATION.STATUS.eq(status.name))
        if (cursor != null) {
            val occurredAt = cursor.occurredAt.atOffset(java.time.ZoneOffset.UTC)
            condition = condition.and(
                CIRCULATION_RESERVATION.PLACED_AT.lt(occurredAt).or(
                    CIRCULATION_RESERVATION.PLACED_AT.eq(occurredAt)
                        .and(CIRCULATION_RESERVATION.ID.lt(cursor.aggregateId)),
                ),
            )
        }
        return dsl.selectFrom(CIRCULATION_RESERVATION)
            .where(condition)
            .orderBy(CIRCULATION_RESERVATION.PLACED_AT.desc(), CIRCULATION_RESERVATION.ID.desc())
            .limit(limit)
            .fetch()
            .map { it.toHistoryDomain() }
    }

    private fun CirculationLoanRecord.toHistoryDomain(): Loan = Loan.restore(
        LoanId(requireNotNull(id)), MemberId(requireNotNull(memberId)), EditionId(requireNotNull(editionId)),
        copyId?.let(::CopyId), LoanStatus.valueOf(requireNotNull(status)),
        requireNotNull(requestedAt).toInstant(), checkedOutAt?.toInstant(), dueAt?.toInstant(),
        returnedAt?.toInstant(), rejectedAt?.toInstant(), requireNotNull(renewalCount), requireNotNull(version),
    )

    private fun CirculationReservationRecord.toHistoryDomain(): Reservation = Reservation.restore(
        ReservationId(requireNotNull(id)), MemberId(requireNotNull(memberId)), EditionId(requireNotNull(editionId)),
        copyId?.let(::CopyId), ReservationStatus.valueOf(requireNotNull(status)),
        requireNotNull(placedAt).toInstant(), readyAt?.toInstant(), expiresAt?.toInstant(),
        fulfilledAt?.toInstant(), cancelledAt?.toInstant(), requireNotNull(version),
    )
}
