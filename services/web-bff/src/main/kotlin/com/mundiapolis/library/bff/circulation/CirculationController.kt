package com.mundiapolis.library.bff.circulation

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
    }
}
