package com.mundiapolis.library.bff.catalog

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogBrowseController(
    private val catalogClient: CatalogClient,
) {
    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) @Size(max = 200) query: String?,
        @RequestParam(required = false) @Size(max = 120) genre: String?,
        @RequestParam(required = false) authorId: UUID?,
        @RequestParam(required = false) availableOnly: Boolean?,
        @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("5.0") minRating: Double?,
        @RequestParam(required = false)
        @Pattern(regexp = "title|rating|publicationYear")
        sortBy: String?,
        @RequestParam(required = false) @Min(0) @Max(10_000) page: Int?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ): ResponseEntity<CatalogSearchView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            catalogClient.search(
                CatalogSearchCriteria(
                    query,
                    genre,
                    authorId,
                    availableOnly,
                    minRating,
                    sortBy,
                    page,
                    limit,
                ),
            ),
        )
}
