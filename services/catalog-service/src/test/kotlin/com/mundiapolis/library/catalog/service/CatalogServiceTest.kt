package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.dto.Author
import com.mundiapolis.library.catalog.dto.CatalogSearchFilters
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work
import com.mundiapolis.library.catalog.service.impl.CatalogServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension
import kotlinx.coroutines.runBlocking

@ExtendWith(SpringExtension::class)
@SpringBootTest
class CatalogServiceTest {

    private val service = CatalogServiceImpl()

    @Test
    fun `test get edition returns correct data`() = runBlocking {
        val edition = service.getEdition("edition-001")
        assertNotNull(edition)
        assertEquals("edition-001", edition?.editionId)
        assertEquals("work-001", edition?.workId)
        assertEquals("Test Edition", edition?.title)
        assertEquals("978-1234567890", edition?.isbn)
        assertEquals("Test Publisher", edition?.publisher)
        assertEquals(2023, edition?.publicationYear)
        assertEquals("English", edition?.language)
        assertEquals(300, edition?.pageCount)
        assertEquals("https://example.com/cover.jpg", edition?.coverUrl)
        assertEquals("#FF0000", edition?.coverColor)
        assertEquals("https://example.com/video.mp4", edition?.videoUrl)
        assertEquals(5, edition?.totalCopies)
        assertEquals(5, edition?.availableCopies)
        assertEquals(true, edition?.isActive)
    }

    @Test
    fun `test get edition returns null for non-existent edition`() = runBlocking {
        val edition = service.getEdition("non-existent-edition")
        assertNull(edition)
    }

    @Test
    fun `test search catalog returns results`() = runBlocking {
        val filters = CatalogSearchFilters(query = "test")
        val result = service.searchCatalog(filters)
        assertNotNull(result)
        assertTrue(result.editions.isNotEmpty())
        assertEquals(1, result.total)
        assertEquals(0, result.page)
        assertEquals(1, result.totalPages)
    }

    @Test
    fun `test search catalog with no matches returns empty results`() = runBlocking {
        val filters = CatalogSearchFilters(query = "nonexistent")
        val result = service.searchCatalog(filters)
        assertNotNull(result)
        assertTrue(result.editions.isEmpty())
        assertEquals(0, result.total)
        assertEquals(0, result.page)
        assertEquals(0, result.totalPages)
    }

    @Test
    fun `test get distinct genres returns correct data`() = runBlocking {
        val genres = service.getDistinctGenres()
        assertNotNull(genres)
        assertFalse(genres.isEmpty())
        assertEquals(1, genres.size)
        assertEquals("Fiction", genres.first())
    }

    @Test
    fun `test search catalog with filters works correctly`() = runBlocking {
        val filters = CatalogSearchFilters(
            genre = "Fiction",
            availableOnly = true,
            minRating = 4.0
        )
        val result = service.searchCatalog(filters)
        assertNotNull(result)
        assertTrue(result.editions.isNotEmpty())
        assertEquals(1, result.total)
    }

    @Test
    fun `test search catalog reports filtered totals and handles pages beyond the result set`() = runBlocking {
        val result = service.searchCatalog(
            CatalogSearchFilters(query = "nonexistent", page = 5, limit = 1)
        )

        assertTrue(result.editions.isEmpty())
        assertEquals(0, result.total)
        assertEquals(5, result.page)
        assertEquals(0, result.totalPages)
    }

    @Test
    fun `test search catalog filters by author id`() = runBlocking {
        val matching = service.searchCatalog(CatalogSearchFilters(authorId = "author-001"))
        val missing = service.searchCatalog(CatalogSearchFilters(authorId = "author-999"))

        assertEquals(1, matching.total)
        assertEquals(0, missing.total)
    }
}
