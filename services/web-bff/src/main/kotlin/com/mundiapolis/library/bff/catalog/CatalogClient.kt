package com.mundiapolis.library.bff.catalog

import com.mundiapolis.library.bff.config.CatalogClientProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpTimeoutException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.IDN
import java.util.Optional
import java.util.UUID

@Component
class CatalogClient(
    private val catalogRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: CatalogClientProperties,
) {
    fun search(authorizedClient: OAuth2AuthorizedClient, criteria: CatalogSearchCriteria): CatalogSearchView = exchange {
        catalogRestClient.get()
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
                .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
                .exchange { _, response ->
                    handleStatus(response.statusCode.value())
                    val body = boundedBody(response)
                    decodeAndValidate(body, criteria)
                }
    }

    fun learningResources(
        authorizedClient: OAuth2AuthorizedClient,
        criteria: LearningResourceSearchCriteria,
    ): LearningResourcePageView = exchange {
        catalogRestClient.get()
            .uri { builder ->
                builder.path("/api/v1/catalog/learning-resources")
                    .queryParamIfPresent("query", Optional.ofNullable(criteria.query))
                    .queryParamIfPresent("category", Optional.ofNullable(criteria.category))
                    .queryParamIfPresent("page", Optional.ofNullable(criteria.page))
                    .queryParamIfPresent("limit", Optional.ofNullable(criteria.limit))
                    .build()
            }
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decodeLearningResourcePage(boundedBody(response), criteria)
            }
    }

    fun learningResource(
        authorizedClient: OAuth2AuthorizedClient,
        resourceId: java.util.UUID,
    ): LearningResourceView = exchange {
        catalogRestClient.get()
            .uri("/api/v1/catalog/learning-resources/{resourceId}", resourceId)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(boundedBody(response), LearningResourceView::class.java).also {
                    if (it.resourceId != resourceId || !validResource(it)) throw CatalogProtocolException()
                }
            }
    }

    fun learningResourceCategories(authorizedClient: OAuth2AuthorizedClient): List<String> = exchange {
        catalogRestClient.get()
            .uri("/api/v1/catalog/learning-resource-categories")
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                val categories = decode(boundedBody(response), Array<String>::class.java).toList()
                if (
                    categories.size > MAXIMUM_CATEGORIES ||
                    categories.any { !validText(it, 1, MAXIMUM_CATEGORY_LENGTH) } ||
                    categories != categories.distinct().sorted()
                ) throw CatalogProtocolException()
                categories
            }
    }

    fun editions(
        authorizedClient: OAuth2AuthorizedClient,
        editionIds: List<UUID>,
    ): List<CatalogEditionView> = exchange {
        catalogRestClient.get()
            .uri { builder ->
                builder.path("/api/v1/catalog/editions")
                    .queryParam("editionId", *editionIds.map(UUID::toString).toTypedArray())
                    .build()
            }
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                val editions = decode(boundedBody(response), Array<CatalogEditionView>::class.java).toList()
                val requestedPositions = editionIds.withIndex().associate { (index, id) -> id to index }
                if (
                    editions.size > editionIds.size ||
                    editions.map { it.editionId }.distinct().size != editions.size ||
                    editions.any { it.editionId !in requestedPositions || !validEdition(it) } ||
                    editions.map { requestedPositions.getValue(it.editionId) } !=
                    editions.map { requestedPositions.getValue(it.editionId) }.sorted()
                ) throw CatalogProtocolException()
                editions
            }
    }

    fun administrativeEditions(
        authorizedClient: OAuth2AuthorizedClient,
        query: String?,
        page: Int?,
        limit: Int?,
    ): CatalogSearchView = exchange {
        val criteria = CatalogSearchCriteria(query, null, null, null, null, "title", page, limit)
        catalogRestClient.get()
            .uri { builder ->
                builder.path("/api/v1/catalog/admin/editions")
                    .queryParamIfPresent("query", Optional.ofNullable(query))
                    .queryParamIfPresent("page", Optional.ofNullable(page))
                    .queryParamIfPresent("limit", Optional.ofNullable(limit))
                    .build()
            }
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decodeAndValidate(boundedBody(response), criteria)
            }
    }

    fun work(authorizedClient: OAuth2AuthorizedClient, workId: UUID): CatalogWorkView = exchange {
        catalogRestClient.get().uri("/api/v1/catalog/works/{workId}", workId)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(boundedBody(response), CatalogWorkView::class.java).also {
                    if (it.workId != workId || !validWork(it)) throw CatalogProtocolException()
                }
            }
    }

    fun createWork(authorizedClient: OAuth2AuthorizedClient, command: CreateCatalogWorkView, idempotencyKey: String): CatalogMutationResult =
        command(authorizedClient, "/api/v1/catalog/works", "POST", null, idempotencyKey, command, "work", command.workId, 0, 201)

    fun updateWork(authorizedClient: OAuth2AuthorizedClient, workId: UUID, expectedVersion: Long, command: UpdateCatalogWorkView, idempotencyKey: String): CatalogMutationResult =
        command(authorizedClient, "/api/v1/catalog/works/$workId", "PUT", expectedVersion, idempotencyKey, command, "work", workId, expectedVersion + 1)

    fun createEdition(authorizedClient: OAuth2AuthorizedClient, workId: UUID, command: CreateCatalogEditionView, idempotencyKey: String): CatalogMutationResult =
        command(authorizedClient, "/api/v1/catalog/works/$workId/editions", "POST", null, idempotencyKey, command, "edition", command.editionId, 0, 201)

    fun updateEdition(authorizedClient: OAuth2AuthorizedClient, editionId: UUID, expectedVersion: Long, command: UpdateCatalogEditionView, idempotencyKey: String): CatalogMutationResult =
        command(authorizedClient, "/api/v1/catalog/editions/$editionId", "PUT", expectedVersion, idempotencyKey, command, "edition", editionId, expectedVersion + 1)

    fun setEditionActive(authorizedClient: OAuth2AuthorizedClient, editionId: UUID, expectedVersion: Long, command: SetCatalogEditionActiveView, idempotencyKey: String): CatalogMutationResult =
        command(authorizedClient, "/api/v1/catalog/editions/$editionId/activation", "POST", expectedVersion, idempotencyKey, command, "edition", editionId, expectedVersion + 1)

    private fun command(
        authorizedClient: OAuth2AuthorizedClient,
        path: String,
        method: String,
        expectedVersion: Long?,
        idempotencyKey: String,
        body: Any,
        aggregateType: String,
        aggregateId: UUID,
        resultVersion: Long,
        expectedStatus: Int = 200,
    ): CatalogMutationResult = exchange {
        validateIdempotencyKey(idempotencyKey)
        if (expectedVersion != null && expectedVersion < 0) throw CatalogInvalidRequestException()
        val request = when (method) {
            "POST" -> catalogRestClient.post().uri(path)
            "PUT" -> catalogRestClient.put().uri(path)
            else -> throw CatalogInvalidRequestException()
        }
        request.header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .apply { expectedVersion?.let { header(HttpHeaders.IF_MATCH, "\"$it\"") } }
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange { _, response ->
                handleStatus(response.statusCode.value(), expectedStatus)
                val replayed = response.headers.getFirst(IDEMPOTENCY_REPLAYED)?.let(::strictBoolean)
                    ?: throw CatalogProtocolException()
                val result = decode(boundedBody(response), CatalogCommandView::class.java)
                val entityTag = response.headers.getFirst(HttpHeaders.ETAG)
                if (result.aggregateType != aggregateType || result.aggregateId != aggregateId ||
                    result.aggregateVersion != resultVersion || entityTag != "\"${result.aggregateVersion}\"") {
                    throw CatalogProtocolException()
                }
                CatalogMutationResult(result, replayed)
            }
    }

    private fun <T> exchange(operation: () -> T): T {
        try {
            return operation()
        } catch (failure: CatalogClientException) {
            throw failure
        } catch (failure: ResourceAccessException) {
            if (failure.hasTimeoutCause()) throw CatalogTimeoutException(failure)
            throw CatalogUnavailableException(failure)
        } catch (failure: RestClientException) {
            throw CatalogProtocolException(failure)
        }
    }

    private fun decodeLearningResourcePage(
        body: ByteArray,
        criteria: LearningResourceSearchCriteria,
    ): LearningResourcePageView {
        val result = decode(body, LearningResourcePageView::class.java)
        val requestedLimit = criteria.limit ?: DEFAULT_RESOURCE_LIMIT
        val requestedPage = criteria.page ?: DEFAULT_PAGE
        val expectedTotalPages = totalPages(result.total, requestedLimit)
        if (
            result.resources.size > requestedLimit ||
            result.total < result.resources.size ||
            result.page != requestedPage ||
            result.totalPages != expectedTotalPages ||
            result.resources.map { it.resourceId }.distinct().size != result.resources.size ||
            result.resources.any { !validResource(it) }
        ) throw CatalogProtocolException()
        return result
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
        val expectedTotalPages = totalPages(result.total, requestedLimit)
        if (
            result.editions.size > requestedLimit ||
            result.total < 0 ||
            result.total < result.editions.size ||
            result.page != requestedPage ||
            result.totalPages != expectedTotalPages ||
            result.editions.any { !validEdition(it) }
        ) {
            throw CatalogProtocolException()
        }
        return result
    }

    private fun validResource(resource: LearningResourceView): Boolean =
        validText(resource.title, 1, 500) &&
            resource.author?.let { validText(it, 1, 500) } != false &&
            resource.description?.let { validText(it, 1, 4_000, allowLines = true) } != false &&
            validText(resource.category, 1, MAXIMUM_CATEGORY_LENGTH) &&
            validText(resource.language, 1, 16) &&
            (resource.coverUrl == null) == (resource.coverAlt == null) &&
            resource.coverAlt?.let { validText(it, 1, 300) } != false &&
            validText(resource.sourceName, 2, 100) &&
            validPublicHttpsUrl(resource.sourceUrl) &&
            resource.coverUrl?.let(::validPublicHttpsUrl) != false &&
            resource.licenseExpression in LICENSE_EXPRESSIONS &&
            validPublicHttpsUrl(resource.licenseUrl) &&
            ((resource.accessMode == LearningResourceAccessMode.DOWNLOAD && resource.readUrl == null) ||
                (resource.accessMode == LearningResourceAccessMode.READ_AT_SOURCE &&
                    resource.readUrl?.let(::validPublicHttpsUrl) == true))

    private fun validEdition(edition: CatalogEditionView): Boolean =
        validText(edition.title, 1, 500) &&
            validText(edition.isbn, 1, 32) &&
            validText(edition.publisher, 1, 300) &&
            edition.publicationYear in 1000..3000 &&
            validText(edition.language, 1, 80) &&
            edition.pageCount in 1..100_000 &&
            edition.coverUrl?.let(::validPublicHttpsUrl) != false &&
            edition.videoUrl?.let(::validPublicHttpsUrl) != false &&
            edition.coverColor?.matches(HEX_COLOR) != false &&
            edition.totalCopies >= 0 &&
            edition.availableCopies in 0..edition.totalCopies &&
            edition.aggregateVersion >= 0

    private fun validWork(work: CatalogWorkView): Boolean =
        validText(work.title, 1, 500) && validText(work.summary, 0, 1_000, allowLines = true) &&
            validText(work.description, 0, 10_000, allowLines = true) && validText(work.genre, 1, 120) &&
            work.rating in 0.0..5.0 && work.aggregateVersion >= 0 && work.authors.isNotEmpty() &&
            work.authors.size <= 20 && work.authors.map { it.id }.distinct().size == work.authors.size &&
            work.authors.all { validText(it.name, 1, 300) && it.bio?.let { bio -> validText(bio, 0, 5_000, allowLines = true) } != false }

    private fun validText(value: String, minimum: Int, maximum: Int, allowLines: Boolean = false): Boolean =
        value.length in minimum..maximum && value == value.trim() &&
            value.none { it.isISOControl() && (!allowLines || it != '\n' && it != '\t') }

    private fun validPublicHttpsUrl(value: String): Boolean {
        if (value.length > 2_048) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = runCatching { IDN.toASCII(uri.host.orEmpty()) }.getOrNull()?.lowercase() ?: return false
        return uri.scheme == "https" && host.isNotBlank() && uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawFragment == null && !uri.rawPath.isNullOrBlank() && uri.normalize().rawPath == uri.rawPath &&
            host != "localhost" && !host.endsWith('.') && !host.endsWith(".localhost") &&
            !host.endsWith(".local") && !host.endsWith(".internal") && !IPV4_LITERAL.matches(host) &&
            !host.contains(':') && !ENCODED_PATH_SEPARATOR.containsMatchIn(uri.rawPath)
    }

    private fun boundedBody(response: org.springframework.http.client.ClientHttpResponse): ByteArray {
        val mediaType = response.headers.contentType
        if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) throw CatalogProtocolException()
        if (response.headers.contentLength > properties.maximumResponseBytes) throw CatalogProtocolException()
        return response.body.readNBytes(properties.maximumResponseBytes + 1)
            .also { if (it.size > properties.maximumResponseBytes) throw CatalogProtocolException() }
    }

    private fun <T> decode(body: ByteArray, type: Class<T>): T = runCatching {
        objectMapper.readValue(body, type)
    }.getOrElse { throw CatalogProtocolException(it) }

    private fun handleStatus(status: Int, expected: Int = 200) {
        when (status) {
            expected -> return
            400 -> throw CatalogInvalidRequestException()
            401 -> throw CatalogReauthenticationRequiredException()
            403 -> throw CatalogAccessDeniedException()
            404 -> throw CatalogNotFoundException()
            409 -> throw CatalogConflictException()
            413 -> throw CatalogInvalidRequestException()
            in RETRYABLE_STATUSES -> throw CatalogUnavailableException()
            else -> throw CatalogProtocolException()
        }
    }

    private fun bearer(client: OAuth2AuthorizedClient): String = "Bearer ${client.accessToken.tokenValue}"

    private fun validateIdempotencyKey(value: String) {
        if (value.length !in 16..128 || value.any { it.code !in 0x21..0x7e }) throw CatalogInvalidRequestException()
    }

    private fun strictBoolean(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw CatalogProtocolException()
    }

    private fun totalPages(total: Int, limit: Int): Int = if (total == 0) 0 else ((total.toLong() + limit - 1) / limit).toInt()

    private fun Throwable.hasTimeoutCause(): Boolean = generateSequence(this) { it.cause }
        .any { it is HttpTimeoutException || it is SocketTimeoutException }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_RESOURCE_LIMIT = 24
        const val DEFAULT_PAGE = 0
        const val MAXIMUM_CATEGORIES = 500
        const val MAXIMUM_CATEGORY_LENGTH = 128
        val IPV4_LITERAL = Regex("^[0-9.]+$")
        val ENCODED_PATH_SEPARATOR = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)
        val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        val LICENSE_EXPRESSIONS = setOf("CC-BY", "CC-BY-SA", "CC0", "PUBLIC-DOMAIN")
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
    }
}

sealed class CatalogClientException(cause: Throwable? = null) : RuntimeException(cause)

sealed class CatalogAuthorizationRejectedException(cause: Throwable? = null) : CatalogClientException(cause)
class CatalogReauthenticationRequiredException : CatalogAuthorizationRejectedException()
class CatalogAccessDeniedException : CatalogAuthorizationRejectedException()
class CatalogInvalidRequestException : CatalogClientException()
class CatalogNotFoundException : CatalogClientException()
class CatalogConflictException : CatalogClientException()

class CatalogTimeoutException(cause: Throwable? = null) : CatalogClientException(cause)

class CatalogUnavailableException(cause: Throwable? = null) : CatalogClientException(cause)

class CatalogProtocolException(cause: Throwable? = null) : CatalogClientException(cause)
