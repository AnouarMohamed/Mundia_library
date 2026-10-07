package com.mundiapolis.library.bff.membership

import com.mundiapolis.library.bff.config.MembershipClientProperties
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

@Component
class MembershipClient(
    private val membershipRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: MembershipClientProperties,
) {
    fun membersForAdministration(
        authorizedClient: OAuth2AuthorizedClient,
        status: AccountStatusView,
        limit: Int?,
        cursor: String?,
    ): AdminMemberPageView {
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAX_PAGE_SIZE || (cursor != null && !CURSOR.matches(cursor))) {
            throw MembershipInvalidRequestException()
        }
        val uri = UriComponentsBuilder.fromPath("/api/v1/members")
            .queryParam("status", status.name)
            .apply {
                limit?.let { queryParam("limit", it) }
                cursor?.let { queryParam("cursor", it) }
            }
            .build().encode().toUriString()
        return exchange {
            membershipRestClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
                .exchange { _, response ->
                    handleStatus(response.statusCode.value())
                    decode(response, AdminMemberPageView::class.java)
                        .also { validateAdminPage(it, pageSize, status) }
                }
        }
    }

    fun changeMemberStatus(
        authorizedClient: OAuth2AuthorizedClient,
        memberId: java.util.UUID,
        expectedVersion: Long,
        idempotencyKey: String,
        command: ChangeMemberStatusView,
    ): MembershipMutationResult {
        if (command.status == AccountStatusView.PENDING || expectedVersion < 0 ||
            idempotencyKey.length !in 16..128 || idempotencyKey.any(Char::isISOControl) ||
            command.reason.trim().length !in 8..500 || command.reason.any(Char::isISOControl)) {
            throw MembershipInvalidRequestException()
        }
        return exchange {
            membershipRestClient.post()
                .uri("/api/v1/members/{memberId}/status", memberId)
                .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
                .header("If-Match", "\"$expectedVersion\"")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(command.copy(reason = command.reason.trim()))
                .exchange { _, response ->
                    handleStatus(response.statusCode.value())
                    val replayed = response.headers.getFirst("Idempotency-Replayed")?.let(::strictBoolean)
                        ?: throw MembershipProtocolException()
                    val result = decode(response, MembershipCommandView::class.java)
                    if (result.memberId != memberId || result.status != command.status ||
                        result.aggregateVersion <= expectedVersion || result.replayed != replayed) {
                        throw MembershipProtocolException()
                    }
                    MembershipMutationResult(result, replayed)
                }
        }
    }

    fun ownProfile(authorizedClient: OAuth2AuthorizedClient): MemberProfileView {
        try {
            return membershipRestClient.get()
                .uri("/api/v1/members/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${authorizedClient.accessToken.tokenValue}")
                .exchange { _, response ->
                    when (response.statusCode.value()) {
                        401 -> throw MembershipReauthenticationRequiredException()
                        403 -> throw MembershipDelegationRejectedException()
                        404 -> throw MembershipProfileNotFoundException()
                    }
                    if (!response.statusCode.is2xxSuccessful) {
                        if (response.statusCode.value() in RETRYABLE_STATUSES) {
                            throw MembershipUnavailableException()
                        }
                        throw MembershipProtocolException()
                    }
                    val mediaType = response.headers.contentType
                    if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                        throw MembershipProtocolException()
                    }
                    val declaredLength = response.headers.contentLength
                    if (declaredLength > properties.maximumResponseBytes) {
                        throw MembershipProtocolException()
                    }
                    val body = response.body.readNBytes(properties.maximumResponseBytes + 1)
                    if (body.size > properties.maximumResponseBytes) {
                        throw MembershipProtocolException()
                    }
                    decodeAndValidate(body)
                }
        } catch (failure: MembershipClientException) {
            throw failure
        } catch (failure: ResourceAccessException) {
            if (failure.hasTimeoutCause()) throw MembershipTimeoutException(failure)
            throw MembershipUnavailableException(failure)
        } catch (failure: RestClientException) {
            throw MembershipProtocolException(failure)
        }
    }

    private fun decodeAndValidate(body: ByteArray): MemberProfileView {
        val profile = decode(body, MemberProfileView::class.java)
        if (
            profile.email.length !in 3..320 ||
            '@' !in profile.email ||
            profile.email.any(Char::isISOControl) ||
            profile.fullName.length !in 1..200 ||
            profile.fullName.any(Char::isISOControl) ||
            profile.universityId < 1 ||
            profile.updatedAt < profile.createdAt
        ) {
            throw MembershipProtocolException()
        }
        return profile
    }

    private fun <T> decode(body: ByteArray, type: Class<T>): T {
        if (body.size > properties.maximumResponseBytes) throw MembershipProtocolException()
        return runCatching { objectMapper.readValue(body, type) }
            .getOrElse { failure -> throw MembershipProtocolException(failure) }
    }

    private fun <T> decode(response: org.springframework.http.client.ClientHttpResponse, type: Class<T>): T {
        val mediaType = response.headers.contentType
        if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON) ||
            response.headers.contentLength > properties.maximumResponseBytes) throw MembershipProtocolException()
        return decode(response.body.readNBytes(properties.maximumResponseBytes + 1), type)
    }

    private fun validateAdminPage(page: AdminMemberPageView, pageSize: Int, status: AccountStatusView) {
        if (page.items.size > pageSize || (page.nextCursor != null && page.items.size != pageSize) ||
            (page.nextCursor != null && !CURSOR.matches(page.nextCursor))) throw MembershipProtocolException()
        page.items.forEach { member ->
            if (member.status != status || member.aggregateVersion < 0 || member.universityId < 1 ||
                member.email.length !in 3..320 || '@' !in member.email || member.email.any(Char::isISOControl) ||
                member.fullName.length !in 1..200 || member.fullName.any(Char::isISOControl) ||
                member.updatedAt < member.createdAt) throw MembershipProtocolException()
        }
        if (page.items.zipWithNext().any { (left, right) ->
                left.createdAt > right.createdAt ||
                    (left.createdAt == right.createdAt && left.memberId >= right.memberId)
            }) throw MembershipProtocolException()
    }

    private fun handleStatus(status: Int) {
        when (status) {
            400 -> throw MembershipInvalidRequestException()
            401 -> throw MembershipReauthenticationRequiredException()
            403 -> throw MembershipDelegationRejectedException()
            404 -> throw MembershipProfileNotFoundException()
            409 -> throw MembershipConflictException()
            in 200..299 -> return
            in RETRYABLE_STATUSES -> throw MembershipUnavailableException()
            else -> throw MembershipProtocolException()
        }
    }

    private fun strictBoolean(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw MembershipProtocolException()
    }

    private fun bearer(client: OAuth2AuthorizedClient): String = "Bearer ${client.accessToken.tokenValue}"

    private fun <T> exchange(operation: () -> T): T = try {
        operation()
    } catch (failure: MembershipClientException) {
        throw failure
    } catch (failure: ResourceAccessException) {
        if (failure.hasTimeoutCause()) throw MembershipTimeoutException(failure)
        throw MembershipUnavailableException(failure)
    } catch (failure: RestClientException) {
        throw MembershipProtocolException(failure)
    }

    private fun Throwable.hasTimeoutCause(): Boolean = generateSequence(this) { it.cause }
        .any { it is HttpTimeoutException || it is SocketTimeoutException }

    private companion object {
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
        const val DEFAULT_PAGE_SIZE = 25
        const val MAX_PAGE_SIZE = 100
        val CURSOR = Regex("[A-Za-z0-9_-]{16,200}")
    }
}

sealed class MembershipClientException(cause: Throwable? = null) : RuntimeException(cause)

sealed class MembershipAuthorizationRejectedException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipReauthenticationRequiredException(cause: Throwable? = null) :
    MembershipAuthorizationRejectedException(cause)

class MembershipDelegationUnavailableException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipDelegationProtocolException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipDelegationRejectedException(cause: Throwable? = null) : MembershipAuthorizationRejectedException(cause)

class MembershipProfileNotFoundException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipConflictException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipInvalidRequestException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipTimeoutException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipUnavailableException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipProtocolException(cause: Throwable? = null) : MembershipClientException(cause)
