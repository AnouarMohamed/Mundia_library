package com.mundiapolis.library.bff.circulation

import com.mundiapolis.library.bff.config.CirculationClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.CIRCULATION_ADMIN_REGISTRATION
import com.mundiapolis.library.bff.membership.MembershipAdministrativeAccess
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service
import java.util.UUID

interface CirculationAdminUseCase {
    fun overview(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse): AdministrativeCirculationOverviewView
    fun loans(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, status: LoanStatusView, limit: Int?, cursor: String?): AdministrativeLoanPageView
    fun reservations(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, status: ReservationStatusView, limit: Int?, cursor: String?): AdministrativeReservationPageView
    fun mutateLoan(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, loanId: UUID, operation: String, idempotencyKey: String): LoanMutationResult
    fun mutateReservation(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, reservationId: UUID, operation: String, idempotencyKey: String): ReservationCommandResult
}

@Service
class CirculationAdminService(
    private val membershipAccess: MembershipAdministrativeAccess,
    private val authorizer: DelegatedClientAuthorizer,
    private val client: CirculationClient,
    private val properties: CirculationClientProperties,
) : CirculationAdminUseCase {
    override fun overview(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse) =
        withClient(authentication, request, response, client::administrativeOverview)

    override fun loans(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, status: LoanStatusView, limit: Int?, cursor: String?) =
        withClient(authentication, request, response) { client.administrativeLoans(it, status, limit, cursor) }

    override fun reservations(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, status: ReservationStatusView, limit: Int?, cursor: String?) =
        withClient(authentication, request, response) { client.administrativeReservations(it, status, limit, cursor) }

    override fun mutateLoan(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, loanId: UUID, operation: String, idempotencyKey: String) =
        withClient(authentication, request, response) { client.mutateAdministrativeLoan(it, loanId, operation, idempotencyKey) }

    override fun mutateReservation(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, reservationId: UUID, operation: String, idempotencyKey: String) =
        withClient(authentication, request, response) { client.mutateAdministrativeReservation(it, reservationId, operation, idempotencyKey) }

    private fun <T> withClient(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        operation: (OAuth2AuthorizedClient) -> T,
    ): T = membershipAccess.withVerifiedAdministrator(authentication, request, response) {
        val authorizedClient = authorizer.authorize(
            CIRCULATION_ADMIN_REGISTRATION,
            properties.maximumDelegatedTokenLifetime,
            authentication,
            request,
            response,
        )
        try {
            operation(authorizedClient)
        } catch (failure: CirculationAuthorizationRejectedException) {
            authorizer.invalidate(CIRCULATION_ADMIN_REGISTRATION, authentication, request, response)
            throw failure
        }
    }
}
