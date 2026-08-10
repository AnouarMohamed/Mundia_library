package com.mundiapolis.library.circulation.application.port.inbound

import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.ReservationCommandExecution
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.ReservationId

data class PlaceReservationCommand(
    val memberId: MemberId,
    val editionId: EditionId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class CancelReservationCommand(
    val reservationId: ReservationId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class FulfillReservationCommand(
    val reservationId: ReservationId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class ExpireReservationCommand(
    val reservationId: ReservationId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

fun interface PlaceReservationUseCase {
    fun place(command: PlaceReservationCommand): ReservationCommandExecution
}

fun interface CancelReservationUseCase {
    fun cancel(command: CancelReservationCommand): ReservationCommandExecution
}

fun interface FulfillReservationUseCase {
    fun fulfill(command: FulfillReservationCommand): ReservationCommandExecution
}

fun interface ExpireReservationUseCase {
    fun expire(command: ExpireReservationCommand): ReservationCommandExecution
}
