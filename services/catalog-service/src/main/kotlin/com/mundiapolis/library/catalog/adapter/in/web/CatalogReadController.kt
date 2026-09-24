package com.mundiapolis.library.catalog.adapter.`in`.web

import com.mundiapolis.library.catalog.dto.CatalogSearchFilters
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work
import com.mundiapolis.library.catalog.service.CatalogService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/catalog")
class CatalogReadController(
    private val catalogService: CatalogService,
) {
    @GetMapping("/works/{workId}")
    @PreAuthorize("hasAuthority('SCOPE_catalog.read')")
    fun work(@PathVariable workId: UUID): Work = catalogService.getWork(workId.toString())
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Work not found")

    @GetMapping("/editions/{editionId}")
    @PreAuthorize("hasAuthority('SCOPE_catalog.read')")
    fun edition(@PathVariable editionId: UUID): Edition = catalogService.getEdition(editionId.toString())
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Edition not found")

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('SCOPE_catalog.search')")
    fun search(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false) authorId: String?,
        @RequestParam(required = false) availableOnly: Boolean?,
        @RequestParam(required = false) minRating: Double?,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) limit: Int?,
    ): CatalogSearchResult = catalogService.searchCatalog(
        CatalogSearchFilters(
            query = query,
            genre = genre,
            authorId = authorId,
            availableOnly = availableOnly,
            minRating = minRating,
            sortBy = sortBy,
            page = page,
            limit = limit,
        ),
    )

    @GetMapping("/genres")
    @PreAuthorize("hasAuthority('SCOPE_catalog.search')")
    fun genres(): List<String> = catalogService.getDistinctGenres()
}
