package com.mundiapolis.library.bff.security

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
) {
    fun authorize(
        registrationId: String,
        maximumTokenLifetime: Duration,
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
            ) ?: throw DelegatedReauthenticationRequiredException()
            val delegated = authorizedClientManager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                    .principal(authentication)
                    .attribute(SUBJECT_TOKEN_ATTRIBUTE, source.accessToken)
                    .servletAttributes(request, response)
                    .build(),
            ) ?: throw DelegatedAuthorizationUnavailableException()
            validateDelegatedToken(delegated.accessToken, maximumTokenLifetime)
            return delegated
        } catch (failure: DelegatedAuthorizationException) {
            throw failure
        } catch (failure: ClientAuthorizationException) {
            if (failure.error.errorCode in REAUTHENTICATION_ERRORS) {
                throw DelegatedReauthenticationRequiredException(failure)
            }
            throw DelegatedAuthorizationUnavailableException(failure)
        } catch (failure: OAuth2AuthorizationException) {
            if (failure.error.errorCode in REAUTHENTICATION_ERRORS) {
                throw DelegatedReauthenticationRequiredException(failure)
            }
            throw DelegatedAuthorizationUnavailableException(failure)
        }
    }

    fun invalidate(
        registrationId: String,
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        authorizedClients.removeAuthorizedClient(registrationId, authentication, request, response)
    }

    private fun OAuth2AuthorizeRequest.Builder.servletAttributes(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): OAuth2AuthorizeRequest.Builder =
        attribute(HttpServletRequest::class.java.name, request)
            .attribute(HttpServletResponse::class.java.name, response)

    private fun validateDelegatedToken(
        token: OAuth2AccessToken,
        maximumTokenLifetime: Duration,
    ) {
        val issuedAt = token.issuedAt ?: throw DelegatedAuthorizationProtocolException()
        val expiresAt = token.expiresAt ?: throw DelegatedAuthorizationProtocolException()
        val lifetime = Duration.between(issuedAt, expiresAt)
        if (
            token.tokenType != OAuth2AccessToken.TokenType.BEARER ||
            lifetime.isNegative ||
            lifetime.isZero ||
            lifetime > maximumTokenLifetime ||
            expiresAt <= Instant.now().plus(MINIMUM_REMAINING_LIFETIME)
        ) {
            throw DelegatedAuthorizationProtocolException()
        }
    }

    private companion object {
        const val LOGIN_REGISTRATION = "institutional"
        val REAUTHENTICATION_ERRORS = setOf("invalid_grant", "invalid_token", "invalid_request")
        val MINIMUM_REMAINING_LIFETIME: Duration = Duration.ofSeconds(5)
    }
}

sealed class DelegatedAuthorizationException(cause: Throwable? = null) : RuntimeException(cause)

class DelegatedReauthenticationRequiredException(cause: Throwable? = null) :
    DelegatedAuthorizationException(cause)

class DelegatedAuthorizationUnavailableException(cause: Throwable? = null) :
    DelegatedAuthorizationException(cause)

class DelegatedAuthorizationProtocolException(cause: Throwable? = null) :
    DelegatedAuthorizationException(cause)
