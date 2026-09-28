package com.mundiapolis.library.circulation.application.port.inbound

import com.mundiapolis.library.circulation.application.model.MemberLoanPage
import com.mundiapolis.library.circulation.application.model.MemberReservationPage
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus

fun interface GetMemberLoansQuery {
    fun get(memberId: MemberId, status: LoanStatus?, limit: Int?, cursor: String?): MemberLoanPage
}

fun interface GetMemberReservationsQuery {
    fun get(
        memberId: MemberId,
        status: ReservationStatus?,
        limit: Int?,
        cursor: String?,
    ): MemberReservationPage
}
