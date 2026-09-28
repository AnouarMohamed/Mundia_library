package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.Reservation

data class MemberLoanPage(
    val memberId: MemberId,
    val items: List<Loan>,
    val nextCursor: String?,
)

data class MemberReservationPage(
    val memberId: MemberId,
    val items: List<Reservation>,
    val nextCursor: String?,
)

class InvalidHistoryQueryException :
    RuntimeException("History cursor or page limit is invalid")
