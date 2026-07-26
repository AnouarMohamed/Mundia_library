package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.application.port.outbound.CirculationStatisticsPort
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
}
