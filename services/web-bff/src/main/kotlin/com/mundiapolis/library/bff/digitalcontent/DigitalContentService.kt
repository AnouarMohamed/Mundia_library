package com.mundiapolis.library.bff.digitalcontent

import com.mundiapolis.library.bff.config.DigitalContentClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.DIGITAL_CONTENT_REGISTRATION
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service
import java.util.UUID

interface DigitalContentSelfServiceUseCase {
    fun availability(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        editionId: UUID,
    ): EditionDownloadAvailabilityView

    fun authorize(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        assetId: UUID,
    ): DownloadAuthorizationView

    fun authorizeExternal(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        resourceId: UUID,
    ): ExternalDownloadAuthorizationView
}

@Service
class DigitalContentService(
    private val authorizer: DelegatedClientAuthorizer,
    private val client: DigitalContentClient,
    private val properties: DigitalContentClientProperties,
) : DigitalContentSelfServiceUseCase {
    override fun availability(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        editionId: UUID,
    ): EditionDownloadAvailabilityView = withClient(authentication, request, response) { authorizedClient ->
        client.availability(authorizedClient, editionId)
    }

    override fun authorize(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        assetId: UUID,
    ): DownloadAuthorizationView = withClient(authentication, request, response) { authorizedClient ->
        client.authorize(authorizedClient, assetId)
    }

    override fun authorizeExternal(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        resourceId: UUID,
    ): ExternalDownloadAuthorizationView = withClient(authentication, request, response) { authorizedClient ->
        client.authorizeExternal(authorizedClient, resourceId)
    }

    private fun <T> withClient(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        operation: (OAuth2AuthorizedClient) -> T,
    ): T {
        val authorizedClient = authorizer.authorize(
            DIGITAL_CONTENT_REGISTRATION,
            properties.maximumDelegatedTokenLifetime,
            authentication,
            request,
            response,
        )
        return try {
            operation(authorizedClient)
        } catch (failure: DigitalContentAuthorizationRejectedException) {
            authorizer.invalidate(DIGITAL_CONTENT_REGISTRATION, authentication, request, response)
            throw failure
        }
    }
}
