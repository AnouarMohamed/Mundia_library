package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.port.inbound.CirculationStatus
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationStatusQuery
import com.mundiapolis.library.circulation.application.port.outbound.CirculationStatisticsPort

class GetCirculationStatusService(
    private val statistics: CirculationStatisticsPort,
) : GetCirculationStatusQuery {
    override fun getStatus(): CirculationStatus = CirculationStatus(
        service = "circulation-service",
        activeLoans = statistics.countActiveLoans(),
    )
}
