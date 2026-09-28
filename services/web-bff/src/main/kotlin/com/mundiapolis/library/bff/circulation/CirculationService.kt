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
