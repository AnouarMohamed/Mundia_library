package com.mundiapolis.library.bff.membership

import com.mundiapolis.library.bff.config.MembershipClientProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException

@Component
class MembershipClient(
    private val membershipRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: MembershipClientProperties,
) {
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
        val profile = runCatching {
            objectMapper.readValue(body, MemberProfileView::class.java)
        }.getOrElse { failure -> throw MembershipProtocolException(failure) }
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

    private fun Throwable.hasTimeoutCause(): Boolean = generateSequence(this) { it.cause }
        .any { it is HttpTimeoutException || it is SocketTimeoutException }

    private companion object {
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
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

class MembershipTimeoutException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipUnavailableException(cause: Throwable? = null) : MembershipClientException(cause)

class MembershipProtocolException(cause: Throwable? = null) : MembershipClientException(cause)
