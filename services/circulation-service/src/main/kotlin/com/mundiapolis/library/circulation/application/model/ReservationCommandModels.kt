package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.Reservation
import com.mundiapolis.library.circulation.domain.model.ReservationId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import java.time.Instant
import java.util.UUID

enum class ReservationOperation(
    val responseStatus: Int,
    val eventType: String,
) {
    PLACE(201, "circulation.reservation.placed"),
    CANCEL(200, "circulation.reservation.cancelled"),
    FULFILL(200, "circulation.reservation.fulfilled"),
    EXPIRE(200, "circulation.reservation.expired"),
}

data class ReservationCommandResult(
    val reservationId: ReservationId,
    val memberId: MemberId,
    val editionId: EditionId,
    val copyId: CopyId?,
    val status: ReservationStatus,
    val placedAt: Instant,
    val readyAt: Instant?,
    val expiresAt: Instant?,
    val fulfilledAt: Instant?,
    val cancelledAt: Instant?,
    val version: Long,
) {
    companion object {
        fun from(reservation: Reservation): ReservationCommandResult = ReservationCommandResult(
            reservation.id,
            reservation.memberId,
            reservation.editionId,
            reservation.copyId,
            reservation.status,
            reservation.placedAt,
            reservation.readyAt,
            reservation.expiresAt,
            reservation.fulfilledAt,
            reservation.cancelledAt,
            reservation.version,
        )
    }
}

data class ReservationCommandExecution(
    val result: ReservationCommandResult,
    val replayed: Boolean,
)

data class StoredReservationIdempotencyResult(
    val operation: ReservationOperation,
    val requestFingerprint: String,
    val result: ReservationCommandResult?,
)

data class ReservationOutboxEvent(
    val id: UUID,
    val result: ReservationCommandResult,
    val eventType: String,
    val occurredAt: Instant,
    val actorFingerprint: String,
)

sealed class ReservationCommandException(message: String) : RuntimeException(message)

class ReservationNotFoundException(id: ReservationId) :
    ReservationCommandException("Reservation ${id.value} was not found")

class OpenReservationAlreadyExistsException :
    ReservationCommandException("The member already has an open reservation for this edition")

class ReservationLimitReachedException(limit: Int) :
    ReservationCommandException("The member has reached the active reservation limit of $limit")

class ReservationStateConflictException(id: ReservationId, status: ReservationStatus) :
    ReservationCommandException("Reservation ${id.value} cannot be changed from state $status")

class ReservationHoldNotExpiredException(id: ReservationId) :
    ReservationCommandException("Reservation ${id.value} has not reached its expiry time")

class ReservationHoldExpiredException(id: ReservationId) :
    ReservationCommandException("Reservation ${id.value} has expired and cannot be fulfilled")

class OpenLoanBlocksReservationException :
    ReservationCommandException("An open loan already exists for this member and edition")

class OpenReservationBlocksLoanException :
    ReservationCommandException("An open reservation already exists for this member and edition")

class PendingReservationBlocksRenewalException :
    ReservationCommandException("Another member is waiting for this edition")
