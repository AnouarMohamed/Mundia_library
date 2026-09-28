package com.mundiapolis.library.circulation.application.port.outbound

import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.Reservation
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import java.time.Instant
import java.util.UUID

data class HistoryCursor(val occurredAt: Instant, val aggregateId: UUID)

interface CirculationHistoryReader {
    fun loans(
        memberId: MemberId,
        status: LoanStatus?,
        cursor: HistoryCursor?,
        limit: Int,
    ): List<Loan>

    fun reservations(
        memberId: MemberId,
        status: ReservationStatus?,
        cursor: HistoryCursor?,
        limit: Int,
    ): List<Reservation>
}
