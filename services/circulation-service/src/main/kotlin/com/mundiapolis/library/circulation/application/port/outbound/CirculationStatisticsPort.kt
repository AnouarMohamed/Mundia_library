package com.mundiapolis.library.circulation.application.port.outbound

fun interface CirculationStatisticsPort {
    fun countActiveLoans(): Long
}
