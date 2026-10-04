package com.mundiapolis.library.bff.digitalcontent

import com.mundiapolis.library.bff.config.DigitalContentClientProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.util.UUID

@Component
class DigitalContentClient(
    private val digitalContentRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: DigitalContentClientProperties,
    private val clock: Clock,
) {
    fun availability(
        authorizedClient: OAuth2AuthorizedClient,
        editionId: UUID,
    ): EditionDownloadAvailabilityView = exchange {
        digitalContentRestClient.get()
            .uri("/api/v1/digital-content/editions/{editionId}/availability", editionId)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, EditionDownloadAvailabilityView::class.java).also { availability ->
                    validateAvailability(availability, editionId)
                }
            }
    }

    fun authorize(
        authorizedClient: OAuth2AuthorizedClient,
        assetId: UUID,
    ): DownloadAuthorizationView = exchange {
        digitalContentRestClient.post()
            .uri("/api/v1/digital-content/assets/{assetId}/authorizations", assetId)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, DownloadAuthorizationView::class.java).also { authorization ->
                    validateAuthorization(authorization, assetId)
                }
            }
    }

    private fun validateAvailability(
        availability: EditionDownloadAvailabilityView,
        expectedEditionId: UUID,
    ) {
        if (
            availability.editionId != expectedEditionId ||
            availability.downloadable != availability.formats.isNotEmpty() ||
            availability.formats.size > 2 ||
            availability.formats.map { it.assetId }.distinct().size != availability.formats.size ||
            availability.formats.map { it.format }.distinct().size != availability.formats.size
        ) {
            throw DigitalContentProtocolException()
        }
        availability.formats.forEach { format ->
            val expectedMediaType = when (format.format) {
                DigitalFormatView.PDF -> "application/pdf"
                DigitalFormatView.EPUB -> "application/epub+zip"
            }
            if (
                format.mediaType != expectedMediaType ||
                format.sizeBytes !in 1..MAXIMUM_ASSET_BYTES ||
                !SHA256.matches(format.sha256) ||
                format.licenseExpression.length !in 2..128 ||
                format.attribution.length !in 2..1_000
            ) {
                throw DigitalContentProtocolException()
            }
        }
    }

    private fun validateAuthorization(
        authorization: DownloadAuthorizationView,
        expectedAssetId: UUID,
    ) {
        val uri = runCatching { URI(authorization.downloadUrl) }
            .getOrElse { throw DigitalContentProtocolException(it) }
        val now = clock.instant()
        if (
            authorization.assetId != expectedAssetId ||
            authorization.expiresAt <= now ||
            authorization.expiresAt > now.plus(properties.maximumSignedUrlLifetime) ||
            !properties.isTrustedDownload(uri) ||
            !DOWNLOAD_PATH.matches(uri.rawPath.orEmpty()) ||
            !validSigningQuery(uri.rawQuery)
        ) {
            throw DigitalContentProtocolException()
        }
    }

    private fun validSigningQuery(rawQuery: String?): Boolean {
        if (rawQuery.isNullOrBlank()) return false
        val parameters = rawQuery.split('&').map { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) return false
            entry.substring(0, separator) to entry.substring(separator + 1)
        }
        if (parameters.map { it.first }.toSet() != SIGNING_PARAMETERS || parameters.size != 4) return false
        return parameters.all { (_, value) -> SIGNING_VALUE.matches(value) } &&
            parameters.toMap()["Hash-Algorithm"] == "SHA256"
    }

    private fun handleStatus(status: Int) {
        when (status) {
            200 -> return
            400 -> throw DigitalContentInvalidRequestException()
            401 -> throw DigitalContentReauthenticationRequiredException()
            403 -> throw DigitalContentAccessDeniedException()
            404 -> throw DigitalContentNotFoundException()
            in RETRYABLE_STATUSES -> throw DigitalContentUnavailableException()
            else -> throw DigitalContentProtocolException()
        }
    }

    private fun <T> decode(
        response: org.springframework.http.client.ClientHttpResponse,
        type: Class<T>,
    ): T {
        val mediaType = response.headers.contentType
        if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            throw DigitalContentProtocolException()
        }
        if (response.headers.contentLength > properties.maximumResponseBytes) {
            throw DigitalContentProtocolException()
        }
        val body = response.body.readNBytes(properties.maximumResponseBytes + 1)
        if (body.size > properties.maximumResponseBytes) throw DigitalContentProtocolException()
        return runCatching { objectMapper.readValue(body, type) }
            .getOrElse { failure -> throw DigitalContentProtocolException(failure) }
    }

    private fun <T> exchange(operation: () -> T): T = try {
        operation()
    } catch (failure: DigitalContentClientException) {
        throw failure
    } catch (failure: ResourceAccessException) {
        if (failure.hasTimeoutCause()) throw DigitalContentTimeoutException(failure)
        throw DigitalContentUnavailableException(failure)
    } catch (failure: RestClientException) {
        throw DigitalContentProtocolException(failure)
    }

    private fun bearer(client: OAuth2AuthorizedClient): String =
        "Bearer ${client.accessToken.tokenValue}"

    private fun Throwable.hasTimeoutCause(): Boolean = generateSequence(this) { it.cause }
        .any { it is HttpTimeoutException || it is SocketTimeoutException }

    private companion object {
        const val MAXIMUM_ASSET_BYTES = 1024L * 1024 * 1024
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val DOWNLOAD_PATH = Regex(
            "^/digital-content/[0-9a-f]{2}/[0-9a-f-]{36}/[0-9a-f]{64}[.](pdf|epub)$",
        )
        val SIGNING_VALUE = Regex("^[A-Za-z0-9_~-]{1,8192}$")
        val SIGNING_PARAMETERS = setOf("Policy", "Signature", "Key-Pair-Id", "Hash-Algorithm")
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
    }
}

sealed class DigitalContentClientException(cause: Throwable? = null) : RuntimeException(cause)
sealed class DigitalContentAuthorizationRejectedException(cause: Throwable? = null) :
    DigitalContentClientException(cause)
class DigitalContentReauthenticationRequiredException(cause: Throwable? = null) :
    DigitalContentAuthorizationRejectedException(cause)
class DigitalContentAccessDeniedException : DigitalContentAuthorizationRejectedException()
class DigitalContentInvalidRequestException : DigitalContentClientException()
class DigitalContentNotFoundException : DigitalContentClientException()
class DigitalContentTimeoutException(cause: Throwable? = null) : DigitalContentClientException(cause)
class DigitalContentUnavailableException(cause: Throwable? = null) : DigitalContentClientException(cause)
class DigitalContentProtocolException(cause: Throwable? = null) : DigitalContentClientException(cause)
