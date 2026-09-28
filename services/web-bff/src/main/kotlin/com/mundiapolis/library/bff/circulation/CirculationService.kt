package com.mundiapolis.library.bff.circulation

import com.mundiapolis.library.bff.config.CirculationClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.CIRCULATION_REGISTRATION
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service

interface CirculationSelfServiceUseCase {
    fun eligibility(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): CirculationEligibilityView

    fun requestLoan(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        command: RequestLoanView,
        idempotencyKey: String,
    ): LoanRequestResult

    fun loans(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: LoanStatusView?,
        limit: Int?,
        cursor: String?,
    ): MemberLoanPageView

    fun cancelLoan(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        loanId: java.util.UUID,
        idempotencyKey: String,
    ): LoanMutationResult

    fun renewLoan(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        loanId: java.util.UUID,
        idempotencyKey: String,
    ): LoanMutationResult

    fun placeReservation(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        command: RequestReservationView,
        idempotencyKey: String,
    ): ReservationCommandResult

    fun reservations(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: ReservationStatusView?,
        limit: Int?,
        cursor: String?,
    ): MemberReservationPageView

    fun cancelReservation(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        reservationId: java.util.UUID,
        idempotencyKey: String,
    ): ReservationCommandResult
}

@Service
class CirculationService(
    private val authorizer: DelegatedClientAuthorizer,
    private val client: CirculationClient,
    private val properties: CirculationClientProperties,
) : CirculationSelfServiceUseCase {
    override fun eligibility(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): CirculationEligibilityView = withClient(authentication, request, response, client::ownEligibility)

    override fun requestLoan(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        command: RequestLoanView,
        idempotencyKey: String,
    ): LoanRequestResult = withClient(authentication, request, response) { authorizedClient ->
        client.requestOwnLoan(authorizedClient, command, idempotencyKey)
    }

    override fun loans(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: LoanStatusView?,
        limit: Int?,
        cursor: String?,
    ): MemberLoanPageView = withClient(authentication, request, response) { authorizedClient ->
        client.ownLoans(authorizedClient, status, limit, cursor)
    }

    override fun cancelLoan(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        loanId: java.util.UUID,
        idempotencyKey: String,
    ): LoanMutationResult = withClient(authentication, request, response) { authorizedClient ->
        client.cancelOwnLoan(authorizedClient, loanId, idempotencyKey)
    }

    override fun renewLoan(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        loanId: java.util.UUID,
        idempotencyKey: String,
    ): LoanMutationResult = withClient(authentication, request, response) { authorizedClient ->
        client.renewOwnLoan(authorizedClient, loanId, idempotencyKey)
    }

    override fun placeReservation(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        command: RequestReservationView,
        idempotencyKey: String,
    ): ReservationCommandResult = withClient(authentication, request, response) { authorizedClient ->
        client.placeOwnReservation(authorizedClient, command, idempotencyKey)
    }

    override fun reservations(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: ReservationStatusView?,
        limit: Int?,
        cursor: String?,
    ): MemberReservationPageView = withClient(authentication, request, response) { authorizedClient ->
        client.ownReservations(authorizedClient, status, limit, cursor)
    }

    override fun cancelReservation(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        reservationId: java.util.UUID,
        idempotencyKey: String,
    ): ReservationCommandResult = withClient(authentication, request, response) { authorizedClient ->
        client.cancelOwnReservation(authorizedClient, reservationId, idempotencyKey)
    }

    private fun <T> withClient(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        operation: (org.springframework.security.oauth2.client.OAuth2AuthorizedClient) -> T,
    ): T {
        val authorizedClient = authorizer.authorize(
            CIRCULATION_REGISTRATION,
            properties.maximumDelegatedTokenLifetime,
            authentication,
            request,
            response,
        )
        return try {
            operation(authorizedClient)
        } catch (failure: CirculationAuthorizationRejectedException) {
            authorizer.invalidate(CIRCULATION_REGISTRATION, authentication, request, response)
            throw failure
        }
    }
}
