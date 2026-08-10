package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.ReservationCommandExecution
import com.mundiapolis.library.circulation.application.model.ReservationCommandResult
import com.mundiapolis.library.circulation.application.port.inbound.CancelReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.CancelReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ExpireReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.ExpireReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.FulfillReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.FulfillReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.PlaceReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.PlaceReservationUseCase
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.ReservationId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/circulation/reservations")
class ReservationCommandController(
    private val placeReservation: PlaceReservationUseCase,
    private val cancelReservation: CancelReservationUseCase,
    private val fulfillReservation: FulfillReservationUseCase,
    private val expireReservation: ExpireReservationUseCase,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @PostMapping
    @PreAuthorize(
        "hasAnyAuthority(" +
            "'SCOPE_circulation.reservation.place'," +
            "'SCOPE_circulation.reservation.place.on-behalf')",
    )
    fun place(
        authentication: JwtAuthenticationToken,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: PlaceReservationRequest,
    ): ResponseEntity<ReservationCommandResponse> {
        val execution = placeReservation.place(
            PlaceReservationCommand(
                MemberId(request.memberId),
                EditionId(request.editionId),
                IdempotencyKey.parse(rawIdempotencyKey),
                principalResolver.forReservationRequest(authentication),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())
    }

    @PostMapping("/{reservationId}/cancel")
    @PreAuthorize(
        "hasAnyAuthority(" +
            "'SCOPE_circulation.reservation.cancel'," +
            "'SCOPE_circulation.reservation.cancel.on-behalf')",
    )
    fun cancel(
        authentication: JwtAuthenticationToken,
        @PathVariable reservationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
    ): ResponseEntity<ReservationCommandResponse> = ok(
        cancelReservation.cancel(
            CancelReservationCommand(
                ReservationId(reservationId),
                IdempotencyKey.parse(rawIdempotencyKey),
                principalResolver.forReservationCancellation(authentication),
            ),
        ),
    )

    @PostMapping("/{reservationId}/fulfill")
    @PreAuthorize("hasAuthority('SCOPE_circulation.reservation.fulfill')")
    fun fulfill(
        authentication: JwtAuthenticationToken,
        @PathVariable reservationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
    ): ResponseEntity<ReservationCommandResponse> = ok(
        fulfillReservation.fulfill(
            FulfillReservationCommand(
                ReservationId(reservationId),
                IdempotencyKey.parse(rawIdempotencyKey),
                principalResolver.forAdministrativeCommand(authentication),
            ),
        ),
    )

    @PostMapping("/{reservationId}/expire")
    @PreAuthorize("hasAuthority('SCOPE_circulation.reservation.expire')")
    fun expire(
        authentication: JwtAuthenticationToken,
        @PathVariable reservationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
    ): ResponseEntity<ReservationCommandResponse> = ok(
        expireReservation.expire(
            ExpireReservationCommand(
                ReservationId(reservationId),
                IdempotencyKey.parse(rawIdempotencyKey),
                principalResolver.forAdministrativeCommand(authentication),
            ),
        ),
    )

    private fun ok(execution: ReservationCommandExecution): ResponseEntity<ReservationCommandResponse> =
        ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())

    private fun ReservationCommandExecution.toResponse(): ReservationCommandResponse =
        ReservationCommandResponse.from(result)

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}

data class PlaceReservationRequest(val memberId: UUID, val editionId: UUID)

data class ReservationCommandResponse(
    val reservationId: UUID,
    val memberId: UUID,
    val editionId: UUID,
    val copyId: UUID?,
    val status: ReservationStatus,
    val placedAt: Instant,
    val readyAt: Instant?,
    val expiresAt: Instant?,
    val fulfilledAt: Instant?,
    val cancelledAt: Instant?,
    val version: Long,
) {
    companion object {
        fun from(result: ReservationCommandResult): ReservationCommandResponse =
            ReservationCommandResponse(
                result.reservationId.value,
                result.memberId.value,
                result.editionId.value,
                result.copyId?.value,
                result.status,
                result.placedAt,
                result.readyAt,
                result.expiresAt,
                result.fulfilledAt,
                result.cancelledAt,
                result.version,
            )
    }
}
