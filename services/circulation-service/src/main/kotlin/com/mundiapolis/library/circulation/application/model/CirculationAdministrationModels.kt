package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.Reservation

data class AdministrativeLoanPage(
    val items: List<Loan>,
    val nextCursor: String?,
)

data class AdministrativeReservationPage(
    val items: List<Reservation>,
    val nextCursor: String?,
)
