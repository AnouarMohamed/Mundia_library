package com.mundiapolis.library.catalog.service.impl

import com.mundiapolis.library.catalog.dto.Author
import com.mundiapolis.library.catalog.dto.CatalogSearchFilters
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work
import com.mundiapolis.library.catalog.service.CatalogService
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class CatalogServiceImpl : CatalogService {

    // In-memory storage for demonstration - to be replaced with actual database implementation
    private val works = ConcurrentHashMap<String, Work>()
    private val editions = ConcurrentHashMap<String, Edition>()
    private val authors = ConcurrentHashMap<String, Author>()

    init {
        // Initialize with some test data
        val testAuthor = Author(
            id = "author-001",
            name = "Test Author",
            bio = "A test author for demonstration"
        )
        authors[testAuthor.id] = testAuthor

        val testWork = Work(
            workId = "work-001",
            title = "Test Work",
            summary = "A test work for demonstration",
            description = "This is a test work used for demonstration purposes",
            genre = "Fiction",
            rating = 4.5,
            authors = listOf(testAuthor)
        )
        works[testWork.workId] = testWork

        val testEdition = Edition(
            editionId = "edition-001",
            workId = testWork.workId,
            title = "Test Edition",
            isbn = "978-1234567890",
            publisher = "Test Publisher",
            publicationYear = 2023,
            language = "English",
            pageCount = 300,
            coverUrl = "https://example.com/cover.jpg",
            coverColor = "#FF0000",
            videoUrl = "https://example.com/video.mp4",
            totalCopies = 5,
            availableCopies = 5,
            isActive = true
        )
        editions[testEdition.editionId] = testEdition
    }

    override suspend fun getEdition(editionId: String): Edition? {
        return editions[editionId]
    }

    override suspend fun searchCatalog(filters: CatalogSearchFilters): CatalogSearchResult {
        // Simple implementation for demonstration
        val filteredEditions = editions.values.filter { it.isActive }
        
        // Apply filters
        val query = filters.query?.lowercase() ?: ""
        val genreFilter = filters.genre?.lowercase() ?: ""
        val availableOnly = filters.availableOnly ?: false
        val minRating = filters.minRating ?: 0.0

        val filtered = filteredEditions.filter { edition ->
            val work = works[edition.workId] ?: return@filter false
            val matchesQuery = query.isEmpty() || 
                edition.title.lowercase().contains(query) || 
                work.title.lowercase().contains(query) || 
                work.summary.lowercase().contains(query) || 
                work.description.lowercase().contains(query) ||
                work.authors.any { it.name.lowercase().contains(query) }
            
            val matchesGenre = genreFilter.isEmpty() || work.genre.lowercase().contains(genreFilter)
            val matchesAvailability = !availableOnly || edition.availableCopies > 0
            val matchesRating = work.rating >= minRating
            
            matchesQuery && matchesGenre && matchesAvailability && matchesRating
        }

        // Sort results
        val sortedEditions = when (filters.sortBy?.lowercase()) {
            "title" -> filtered.sortedBy { it.title }
            "rating" -> filtered.sortedByDescending { works[it.workId]?.rating ?: 0.0 }
            "publicationYear" -> filtered.sortedByDescending { it.publicationYear }
            else -> filtered
        }

        // Apply pagination
        val page = filters.page ?: 0
        val limit = filters.limit ?: 20
        val fromIndex = page * limit
        val toIndex = min(fromIndex + limit, sortedEditions.size)
        val pagedEditions = sortedEditions.subList(fromIndex, toIndex)

        return CatalogSearchResult(
            editions = pagedEditions,
            total = filteredEditions.size,
            page = page,
            totalPages = (filteredEditions.size + limit - 1) / limit
        )
    }

    override suspend fun getDistinctGenres(): List<String> {
        return works.values.map { it.genre }.distinct().sorted()
    }
}
