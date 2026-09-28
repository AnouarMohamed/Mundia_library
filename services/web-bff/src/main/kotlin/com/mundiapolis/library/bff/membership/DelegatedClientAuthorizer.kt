package com.mundiapolis.library.bff.membership

import com.mundiapolis.library.bff.config.MembershipClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.MEMBERSHIP_REGISTRATION
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.SUBJECT_TOKEN_ATTRIBUTE
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class DelegatedClientAuthorizer(
    private val authorizedClientManager: OAuth2AuthorizedClientManager,
    private val authorizedClients: OAuth2AuthorizedClientRepository,
    private val properties: MembershipClientProperties,
) {
    fun authorizeMembership(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): OAuth2AuthorizedClient {
        try {
            val source = authorizedClientManager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId(LOGIN_REGISTRATION)
                    .principal(authentication)
                    .servletAttributes(request, response)
                    .build(),
            ) ?: throw MembershipReauthenticationRequiredException()
            val delegated = authorizedClientManager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId(MEMBERSHIP_REGISTRATION)
                    .principal(authentication)
                    .attribute(SUBJECT_TOKEN_ATTRIBUTE, source.accessToken)
                    .servletAttributes(request, response)
                    .build(),
            ) ?: throw MembershipDelegationUnavailableException()
            validateDelegatedToken(delegated.accessToken)
            return delegated
        } catch (failure: MembershipClientException) {
            throw failure
        } catch (failure: ClientAuthorizationException) {
            if (failure.error.errorCode in REAUTHENTICATION_ERRORS) {
                throw MembershipReauthenticationRequiredException(failure)
            }
            throw MembershipDelegationUnavailableException(failure)
        } catch (failure: OAuth2AuthorizationException) {
            if (failure.error.errorCode in REAUTHENTICATION_ERRORS) {
                throw MembershipReauthenticationRequiredException(failure)
            }
            throw MembershipDelegationUnavailableException(failure)
        }
    }

    fun invalidateMembership(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        authorizedClients.removeAuthorizedClient(
            MEMBERSHIP_REGISTRATION,
            authentication,
            request,
            response,
        )
    }

    private fun OAuth2AuthorizeRequest.Builder.servletAttributes(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): OAuth2AuthorizeRequest.Builder =
        attribute(HttpServletRequest::class.java.name, request)
            .attribute(HttpServletResponse::class.java.name, response)

    private fun validateDelegatedToken(token: OAuth2AccessToken) {
        val issuedAt = token.issuedAt ?: throw MembershipDelegationProtocolException()
        val expiresAt = token.expiresAt ?: throw MembershipDelegationProtocolException()
        val lifetime = Duration.between(issuedAt, expiresAt)
        if (
            token.tokenType != OAuth2AccessToken.TokenType.BEARER ||
            lifetime.isNegative ||
            lifetime.isZero ||
            lifetime > properties.maximumDelegatedTokenLifetime ||
            expiresAt <= Instant.now().plus(MINIMUM_REMAINING_LIFETIME)
        ) {
            throw MembershipDelegationProtocolException()
        }
    }

    private companion object {
        const val LOGIN_REGISTRATION = "institutional"
        val REAUTHENTICATION_ERRORS = setOf("invalid_grant", "invalid_token", "invalid_request")
        val MINIMUM_REMAINING_LIFETIME: Duration = Duration.ofSeconds(5)
    }
}
