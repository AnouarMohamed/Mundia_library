package com.mundiapolis.library.circulation.application.port.inbound

data class CirculationStatus(
    val service: String,
    val activeLoans: Long,
)

fun interface GetCirculationStatusQuery {
    fun getStatus(): CirculationStatus
}
