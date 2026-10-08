package com.mundiapolis.library.circulation.application.port.outbound

interface CirculationStatisticsPort {
    fun countActiveLoans(): Long

    fun administrativeSnapshot(): AdministrativeCirculationSnapshot
}

data class AdministrativeCirculationSnapshot(
    val requestedLoans: Long,
    val activeLoans: Long,
    val overdueLoans: Long,
    val waitingReservations: Long,
    val readyReservations: Long,
)
