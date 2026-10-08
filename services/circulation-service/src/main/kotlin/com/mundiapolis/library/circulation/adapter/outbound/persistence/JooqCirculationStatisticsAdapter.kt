package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.application.port.outbound.CirculationStatisticsPort
import com.mundiapolis.library.circulation.application.port.outbound.AdministrativeCirculationSnapshot
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_RESERVATION
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class JooqCirculationStatisticsAdapter(
    private val dsl: DSLContext,
) : CirculationStatisticsPort {
    override fun countActiveLoans(): Long = dsl
        .selectCount()
        .from(CIRCULATION_LOAN)
        .where(CIRCULATION_LOAN.STATUS.eq("ACTIVE"))
        .fetchSingle()
        .value1()
        .toLong()

    override fun administrativeSnapshot(): AdministrativeCirculationSnapshot {
        val loans = dsl.select(
            org.jooq.impl.DSL.count().filterWhere(CIRCULATION_LOAN.STATUS.eq("REQUESTED")),
            org.jooq.impl.DSL.count().filterWhere(CIRCULATION_LOAN.STATUS.eq("ACTIVE")),
            org.jooq.impl.DSL.count().filterWhere(
                CIRCULATION_LOAN.STATUS.eq("ACTIVE").and(
                    CIRCULATION_LOAN.DUE_AT.lt(org.jooq.impl.DSL.currentOffsetDateTime()),
                ),
            ),
        ).from(CIRCULATION_LOAN).fetchSingle()
        val reservations = dsl.select(
            org.jooq.impl.DSL.count().filterWhere(CIRCULATION_RESERVATION.STATUS.eq("WAITING")),
            org.jooq.impl.DSL.count().filterWhere(CIRCULATION_RESERVATION.STATUS.eq("READY")),
        ).from(CIRCULATION_RESERVATION).fetchSingle()
        return AdministrativeCirculationSnapshot(
            requestedLoans = loans.value1().toLong(),
            activeLoans = loans.value2().toLong(),
            overdueLoans = loans.value3().toLong(),
            waitingReservations = reservations.value1().toLong(),
            readyReservations = reservations.value2().toLong(),
        )
    }
}
