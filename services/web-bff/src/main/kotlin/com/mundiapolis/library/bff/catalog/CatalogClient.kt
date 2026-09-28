package com.mundiapolis.library.bff.catalog

import com.mundiapolis.library.bff.config.CatalogClientProperties
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId
import org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver.principal
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpTimeoutException
import java.net.SocketTimeoutException
import java.util.Optional

@Component
class CatalogClient(
    private val catalogRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: CatalogClientProperties,
) {
    fun search(criteria: CatalogSearchCriteria): CatalogSearchView {
        try {
            return catalogRestClient.get()
                .uri { builder ->
                    builder.path("/api/v1/catalog/search")
                        .queryParamIfPresent("query", Optional.ofNullable(criteria.query))
                        .queryParamIfPresent("genre", Optional.ofNullable(criteria.genre))
                        .queryParamIfPresent("authorId", Optional.ofNullable(criteria.authorId))
                        .queryParamIfPresent("availableOnly", Optional.ofNullable(criteria.availableOnly))
                        .queryParamIfPresent("minRating", Optional.ofNullable(criteria.minRating))
                        .queryParamIfPresent("sortBy", Optional.ofNullable(criteria.sortBy))
                        .queryParamIfPresent("page", Optional.ofNullable(criteria.page))
                        .queryParamIfPresent("limit", Optional.ofNullable(criteria.limit))
                        .build()
                }
                .attributes(clientRegistrationId(CATALOG_REGISTRATION))
                .attributes(principal(BFF_SERVICE_PRINCIPAL))
                .exchange { _, response ->
                    if (!response.statusCode.is2xxSuccessful) {
                        if (response.statusCode.value() in RETRYABLE_STATUSES) {
                            throw CatalogUnavailableException()
                        }
                        throw CatalogProtocolException()
                    }
                    val mediaType = response.headers.contentType
                    if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                        throw CatalogProtocolException()
                    }
                    val declaredLength = response.headers.contentLength
                    if (declaredLength > properties.maximumResponseBytes) {
                        throw CatalogProtocolException()
                    }
                    val body = response.body.readNBytes(properties.maximumResponseBytes + 1)
                    if (body.size > properties.maximumResponseBytes) {
                        throw CatalogProtocolException()
                    }
                    decodeAndValidate(body, criteria)
                }
        } catch (failure: CatalogClientException) {
            throw failure
        } catch (failure: ClientAuthorizationException) {
            throw CatalogUnavailableException(failure)
        } catch (failure: ResourceAccessException) {
            if (failure.hasTimeoutCause()) throw CatalogTimeoutException(failure)
            throw CatalogUnavailableException(failure)
        } catch (failure: RestClientException) {
            throw CatalogProtocolException(failure)
        }
    }

    private fun decodeAndValidate(
        body: ByteArray,
        criteria: CatalogSearchCriteria,
    ): CatalogSearchView {
        val result = runCatching {
            objectMapper.readValue(body, CatalogSearchView::class.java)
        }.getOrElse { failure -> throw CatalogProtocolException(failure) }
        val requestedLimit = criteria.limit ?: DEFAULT_LIMIT
        val requestedPage = criteria.page ?: DEFAULT_PAGE
        val expectedTotalPages = if (result.total == 0) {
            0
        } else {
            ((result.total.toLong() + requestedLimit - 1) / requestedLimit).toInt()
        }
        if (
            result.editions.size > requestedLimit ||
            result.total < 0 ||
            result.total < result.editions.size ||
            result.page != requestedPage ||
            result.totalPages != expectedTotalPages ||
            result.editions.any { edition ->
                edition.pageCount < 1 ||
                    edition.totalCopies < 0 ||
                    edition.availableCopies !in 0..edition.totalCopies
            }
        ) {
            throw CatalogProtocolException()
        }
        return result
    }

    private fun Throwable.hasTimeoutCause(): Boolean = generateSequence(this) { it.cause }
        .any { it is HttpTimeoutException || it is SocketTimeoutException }

    private companion object {
        const val CATALOG_REGISTRATION = "catalog-service"
        const val BFF_SERVICE_PRINCIPAL = "web-bff"
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_PAGE = 0
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
    }
}

sealed class CatalogClientException(cause: Throwable? = null) : RuntimeException(cause)

class CatalogTimeoutException(cause: Throwable? = null) : CatalogClientException(cause)

class CatalogUnavailableException(cause: Throwable? = null) : CatalogClientException(cause)

class CatalogProtocolException(cause: Throwable? = null) : CatalogClientException(cause)
