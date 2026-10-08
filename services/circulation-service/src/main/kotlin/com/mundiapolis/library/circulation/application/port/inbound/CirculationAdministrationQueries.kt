package com.mundiapolis.library.circulation.application.port.inbound

import com.mundiapolis.library.circulation.application.model.AdministrativeLoanPage
import com.mundiapolis.library.circulation.application.model.AdministrativeReservationPage
import com.mundiapolis.library.circulation.application.port.outbound.AdministrativeCirculationSnapshot
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.ReservationStatus

fun interface GetAdministrativeLoanQueueQuery {
    fun get(status: LoanStatus, limit: Int?, cursor: String?): AdministrativeLoanPage
}

fun interface GetAdministrativeReservationQueueQuery {
    fun get(status: ReservationStatus, limit: Int?, cursor: String?): AdministrativeReservationPage
}

fun interface GetAdministrativeCirculationOverviewQuery {
    fun get(): AdministrativeCirculationSnapshot
}
