package com.mundiapolis.library.bff.circulation

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/circulation")
class CirculationAdminController(private val circulation: CirculationAdminUseCase) {
    @GetMapping("/overview")
    fun overview(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse) =
        noStore(circulation.overview(authentication, request, response))

    @GetMapping("/loans")
    fun loans(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(defaultValue = "REQUESTED") status: LoanStatusView,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ) = noStore(circulation.loans(authentication, request, response, status, limit, cursor))

    @GetMapping("/reservations")
    fun reservations(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(defaultValue = "READY") status: ReservationStatusView,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ) = noStore(circulation.reservations(authentication, request, response, status, limit, cursor))

    @PostMapping("/loans/{loanId}/{operation:approve|reject|return}")
    fun mutateLoan(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable loanId: UUID,
        @PathVariable operation: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<LoanCommandView> {
        val result = circulation.mutateLoan(authentication, request, response, loanId, operation, idempotencyKey)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Idempotency-Replayed", result.idempotencyReplayed.toString()).body(result.loan)
    }

    @PostMapping("/reservations/{reservationId}/{operation:fulfill|expire}")
    fun mutateReservation(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable reservationId: UUID,
        @PathVariable operation: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<ReservationCommandView> {
        val result = circulation.mutateReservation(authentication, request, response, reservationId, operation, idempotencyKey)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Idempotency-Replayed", result.idempotencyReplayed.toString()).body(result.reservation)
    }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
