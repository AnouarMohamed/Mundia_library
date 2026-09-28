package com.mundiapolis.library.bff.circulation

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/circulation")
class CirculationController(
    private val circulation: CirculationSelfServiceUseCase,
) {
    @GetMapping("/eligibility")
    fun eligibility(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<CirculationEligibilityView> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(circulation.eligibility(authentication, request, response))

    @GetMapping("/loans")
    fun loans(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) status: LoanStatusView?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<MemberLoanPageView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(circulation.loans(authentication, request, response, status, limit, cursor))

    @PostMapping("/loans")
    fun requestLoan(
        authentication: OAuth2AuthenticationToken,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @RequestBody request: RequestLoanView,
    ): ResponseEntity<LoanCommandView> {
        val result = circulation.requestLoan(
            authentication,
            servletRequest,
            servletResponse,
            request,
            idempotencyKey,
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .header(IDEMPOTENCY_REPLAYED, result.idempotencyReplayed.toString())
            .body(result.loan)
    }

    @PostMapping("/loans/{loanId}/cancel")
    fun cancelLoan(
        authentication: OAuth2AuthenticationToken,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @PathVariable loanId: UUID,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
    ): ResponseEntity<LoanCommandView> = mutationResponse(
        circulation.cancelLoan(
            authentication,
            servletRequest,
            servletResponse,
            loanId,
            idempotencyKey,
        ),
    )

    @PostMapping("/loans/{loanId}/renew")
    fun renewLoan(
        authentication: OAuth2AuthenticationToken,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @PathVariable loanId: UUID,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
    ): ResponseEntity<LoanCommandView> = mutationResponse(
        circulation.renewLoan(
            authentication,
            servletRequest,
            servletResponse,
            loanId,
            idempotencyKey,
        ),
    )

    private fun mutationResponse(result: LoanMutationResult): ResponseEntity<LoanCommandView> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(IDEMPOTENCY_REPLAYED, result.idempotencyReplayed.toString())
            .body(result.loan)

    @PostMapping("/reservations")
    fun placeReservation(
        authentication: OAuth2AuthenticationToken,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @RequestBody request: RequestReservationView,
    ): ResponseEntity<ReservationCommandView> {
        val result = circulation.placeReservation(
            authentication,
            servletRequest,
            servletResponse,
            request,
            idempotencyKey,
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .header(IDEMPOTENCY_REPLAYED, result.idempotencyReplayed.toString())
            .body(result.reservation)
    }

    @GetMapping("/reservations")
    fun reservations(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) status: ReservationStatusView?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<MemberReservationPageView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(circulation.reservations(authentication, request, response, status, limit, cursor))

    @PostMapping("/reservations/{reservationId}/cancel")
    fun cancelReservation(
        authentication: OAuth2AuthenticationToken,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @PathVariable reservationId: UUID,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
    ): ResponseEntity<ReservationCommandView> {
        val result = circulation.cancelReservation(
            authentication,
            servletRequest,
            servletResponse,
            reservationId,
            idempotencyKey,
        )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(IDEMPOTENCY_REPLAYED, result.idempotencyReplayed.toString())
            .body(result.reservation)
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
    }
}
