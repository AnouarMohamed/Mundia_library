package com.mundiapolis.library.bff.notification

import com.mundiapolis.library.bff.config.NotificationClientProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.UUID

@Component
class NotificationClient(
    private val notificationRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: NotificationClientProperties,
) {
    fun ownNotifications(
        authorizedClient: OAuth2AuthorizedClient,
        status: NotificationReadStatusView?,
        limit: Int?,
        cursor: String?,
    ): NotificationPageView = exchange {
        val pageSize = validatePageRequest(limit, cursor)
        val uri = UriComponentsBuilder.fromPath("/api/v1/notifications/me").apply {
            status?.let { queryParam("status", it.name) }
            limit?.let { queryParam("limit", it) }
            cursor?.let { queryParam("cursor", it) }
        }.build().encode().toUriString()
        notificationRestClient.get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, NotificationPageView::class.java).also { validatePage(it, pageSize) }
            }
    }

    fun markRead(
        authorizedClient: OAuth2AuthorizedClient,
        notificationId: UUID,
    ): NotificationItemView = exchange {
        notificationRestClient.patch()
            .uri("/api/v1/notifications/{notificationId}/read", notificationId)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, NotificationItemView::class.java).also { item ->
                    validateItem(item)
                    if (item.notificationId != notificationId || item.readAt == null) {
                        throw NotificationProtocolException()
                    }
                }
            }
    }

    fun ownPreference(authorizedClient: OAuth2AuthorizedClient): VersionedNotificationPreference =
        exchange {
            preferenceExchange {
                notificationRestClient.get()
                    .uri("/api/v1/notifications/preferences/me")
                    .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            }
        }

    fun updateOwnPreference(
        authorizedClient: OAuth2AuthorizedClient,
        entityTag: String,
        update: UpdateNotificationPreferenceView,
    ): VersionedNotificationPreference = exchange {
        if (!validEntityTag(entityTag)) throw NotificationInvalidRequestException()
        preferenceExchange {
            notificationRestClient.put()
                .uri("/api/v1/notifications/preferences/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
                .header(HttpHeaders.IF_MATCH, entityTag)
                .contentType(MediaType.APPLICATION_JSON)
                .body(update)
        }
    }

    private fun preferenceExchange(
        request: () -> RestClient.RequestHeadersSpec<*>,
    ): VersionedNotificationPreference = request().exchange { _, response ->
        handleStatus(response.statusCode.value())
        val preference = decode(response, NotificationPreferenceView::class.java)
        validatePreference(preference)
        val entityTag = response.headers.eTag ?: throw NotificationProtocolException()
        if (!validEntityTag(entityTag)) throw NotificationProtocolException()
        if (entityTag != "\"${preference.version}\"") throw NotificationProtocolException()
        VersionedNotificationPreference(preference, entityTag)
    }

    private fun validatePageRequest(limit: Int?, cursor: String?): Int {
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAXIMUM_PAGE_SIZE) throw NotificationInvalidRequestException()
        if (cursor != null && (cursor.isBlank() || !CURSOR.matches(cursor))) {
            throw NotificationInvalidRequestException()
        }
        return pageSize
    }

    private fun validatePage(page: NotificationPageView, pageSize: Int) {
        if (
            page.items.size > pageSize ||
            (page.nextCursor != null && page.items.size != pageSize) ||
            (page.nextCursor != null && !CURSOR.matches(page.nextCursor))
        ) {
            throw NotificationProtocolException()
        }
        page.items.forEach(::validateItem)
    }

    private fun validateItem(item: NotificationItemView) {
        if (
            item.subject.isBlank() || item.subject.length > 160 ||
            item.body.isBlank() || item.body.length > 2_000 ||
            item.createdAt < item.occurredAt ||
            (item.readAt != null && item.readAt < item.createdAt)
        ) {
            throw NotificationProtocolException()
        }
    }

    private fun validatePreference(preference: NotificationPreferenceView) {
        if (preference.version < 0 || (preference.version == 0L) != (preference.updatedAt == null)) {
            throw NotificationProtocolException()
        }
    }

    private fun validEntityTag(entityTag: String): Boolean = ENTITY_TAG.matches(entityTag)

    private fun handleStatus(status: Int) {
        when (status) {
            200 -> return
            400 -> throw NotificationInvalidRequestException()
            401 -> throw NotificationReauthenticationRequiredException()
            403 -> throw NotificationAccessDeniedException()
            404 -> throw NotificationNotFoundException()
            409 -> throw NotificationConflictException()
            428 -> throw NotificationPreconditionRequiredException()
            in RETRYABLE_STATUSES -> throw NotificationUnavailableException()
            else -> throw NotificationProtocolException()
        }
    }

    private fun <T> decode(
        response: org.springframework.http.client.ClientHttpResponse,
        type: Class<T>,
    ): T {
        val mediaType = response.headers.contentType
        if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            throw NotificationProtocolException()
        }
        if (response.headers.contentLength > properties.maximumResponseBytes) {
            throw NotificationProtocolException()
        }
        val body = response.body.readNBytes(properties.maximumResponseBytes + 1)
        if (body.size > properties.maximumResponseBytes) throw NotificationProtocolException()
        return runCatching { objectMapper.readValue(body, type) }
            .getOrElse { failure -> throw NotificationProtocolException(failure) }
    }

    private fun <T> exchange(operation: () -> T): T = try {
        operation()
    } catch (failure: NotificationClientException) {
        throw failure
    } catch (failure: ResourceAccessException) {
        if (failure.hasTimeoutCause()) throw NotificationTimeoutException(failure)
        throw NotificationUnavailableException(failure)
    } catch (failure: RestClientException) {
        throw NotificationProtocolException(failure)
    }

    private fun OAuth2AuthorizedClient.bearerToken(): String = accessToken.tokenValue

    private fun bearer(client: OAuth2AuthorizedClient): String = "Bearer ${client.bearerToken()}"

    private fun Throwable.hasTimeoutCause(): Boolean = generateSequence(this) { it.cause }
        .any { it is HttpTimeoutException || it is SocketTimeoutException }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAXIMUM_PAGE_SIZE = 100
        val CURSOR = Regex("^[A-Za-z0-9_-]{1,128}$")
        val ENTITY_TAG = Regex("^\"(?:0|[1-9][0-9]{0,18})\"$")
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
    }
}

sealed class NotificationClientException(cause: Throwable? = null) : RuntimeException(cause)
sealed class NotificationAuthorizationRejectedException(cause: Throwable? = null) :
    NotificationClientException(cause)
class NotificationReauthenticationRequiredException(cause: Throwable? = null) :
    NotificationAuthorizationRejectedException(cause)
class NotificationAccessDeniedException : NotificationAuthorizationRejectedException()
class NotificationInvalidRequestException : NotificationClientException()
class NotificationNotFoundException : NotificationClientException()
class NotificationConflictException : NotificationClientException()
class NotificationPreconditionRequiredException : NotificationClientException()
class NotificationTimeoutException(cause: Throwable? = null) : NotificationClientException(cause)
class NotificationUnavailableException(cause: Throwable? = null) : NotificationClientException(cause)
class NotificationProtocolException(cause: Throwable? = null) : NotificationClientException(cause)
