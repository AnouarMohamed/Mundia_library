package com.mundiapolis.library.bff.notification

import com.mundiapolis.library.bff.config.NotificationClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.NOTIFICATION_REGISTRATION
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service
import java.util.UUID

interface NotificationSelfServiceUseCase {
    fun notifications(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: NotificationReadStatusView?,
        limit: Int?,
        cursor: String?,
    ): NotificationPageView

    fun markRead(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        notificationId: UUID,
    ): NotificationItemView

    fun preference(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): VersionedNotificationPreference

    fun updatePreference(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        entityTag: String,
        update: UpdateNotificationPreferenceView,
    ): VersionedNotificationPreference
}

@Service
class NotificationService(
    private val authorizer: DelegatedClientAuthorizer,
    private val client: NotificationClient,
    private val properties: NotificationClientProperties,
) : NotificationSelfServiceUseCase {
    override fun notifications(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: NotificationReadStatusView?,
        limit: Int?,
        cursor: String?,
    ): NotificationPageView = withClient(authentication, request, response) { authorizedClient ->
        client.ownNotifications(authorizedClient, status, limit, cursor)
    }

    override fun markRead(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        notificationId: UUID,
    ): NotificationItemView = withClient(authentication, request, response) { authorizedClient ->
        client.markRead(authorizedClient, notificationId)
    }

    override fun preference(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): VersionedNotificationPreference = withClient(authentication, request, response, client::ownPreference)

    override fun updatePreference(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        entityTag: String,
        update: UpdateNotificationPreferenceView,
    ): VersionedNotificationPreference = withClient(authentication, request, response) { authorizedClient ->
        client.updateOwnPreference(authorizedClient, entityTag, update)
    }

    private fun <T> withClient(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        operation: (OAuth2AuthorizedClient) -> T,
    ): T {
        val authorizedClient = authorizer.authorize(
            NOTIFICATION_REGISTRATION,
            properties.maximumDelegatedTokenLifetime,
            authentication,
            request,
            response,
        )
        return try {
            operation(authorizedClient)
        } catch (failure: NotificationAuthorizationRejectedException) {
            authorizer.invalidate(NOTIFICATION_REGISTRATION, authentication, request, response)
            throw failure
        }
    }
}
