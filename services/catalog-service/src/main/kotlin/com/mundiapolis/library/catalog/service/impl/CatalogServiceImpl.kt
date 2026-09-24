package com.mundiapolis.library.catalog.service.impl

import com.mundiapolis.library.catalog.adapter.outbound.persistence.JooqCatalogRepository
import com.mundiapolis.library.catalog.dto.CatalogSearchFilters
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work
import com.mundiapolis.library.catalog.service.CatalogService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CatalogServiceImpl(
    private val repository: JooqCatalogRepository,
) : CatalogService {
    override fun getWork(workId: String): Work? = repository.findWork(workId.toIdentifier("workId"))

    override fun getEdition(editionId: String): Edition? =
        repository.findEdition(editionId.toIdentifier("editionId"))

    override fun searchCatalog(filters: CatalogSearchFilters): CatalogSearchResult =
        repository.search(filters.normalized())

    override fun getDistinctGenres(): List<String> = repository.findDistinctGenres()

    private fun CatalogSearchFilters.normalized(): CatalogSearchFilters {
        val normalizedQuery = query?.trim()?.takeIf(String::isNotEmpty)
        val normalizedGenre = genre?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedQuery == null || normalizedQuery.length <= MAX_QUERY_LENGTH) {
            "query must not exceed $MAX_QUERY_LENGTH characters"
        }
        require(normalizedGenre == null || normalizedGenre.length <= MAX_GENRE_LENGTH) {
            "genre must not exceed $MAX_GENRE_LENGTH characters"
        }
        val normalizedAuthor = authorId?.toIdentifier("authorId")?.toString()
        val normalizedRating = minRating ?: 0.0
        require(normalizedRating in 0.0..5.0) { "minRating must be between 0 and 5" }
        val normalizedSort = sortBy?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedSort == null || normalizedSort in ALLOWED_SORTS) {
            "sortBy must be one of title, rating, or publicationYear"
        }
        val normalizedPage = page ?: 0
        require(normalizedPage in 0..MAX_PAGE) { "page must be between 0 and $MAX_PAGE" }
        val normalizedLimit = limit ?: DEFAULT_LIMIT
        require(normalizedLimit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }

        return CatalogSearchFilters(
            query = normalizedQuery,
            genre = normalizedGenre,
            authorId = normalizedAuthor,
            availableOnly = availableOnly ?: false,
            minRating = normalizedRating,
            sortBy = normalizedSort,
            page = normalizedPage,
            limit = normalizedLimit,
        )
    }

    private fun String.toIdentifier(name: String): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse { throw IllegalArgumentException("$name must be a canonical UUID") }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
        const val MAX_GENRE_LENGTH = 120
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_PAGE = 10_000
        val ALLOWED_SORTS = setOf("title", "rating", "publicationYear")
    }
}
