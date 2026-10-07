package com.mundiapolis.library.bff.catalog

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PathVariable
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogBrowseController(
    private val catalog: CatalogBrowseUseCase,
) {
    @GetMapping("/search")
    fun search(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
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
            catalog.search(
                authentication,
                request,
                response,
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

    @GetMapping("/learning-resources")
    fun learningResources(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) @Size(max = 200) query: String?,
        @RequestParam(required = false) @Size(max = 128) category: String?,
        @RequestParam(required = false) @Min(0) @Max(10_000) page: Int?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ): ResponseEntity<LearningResourcePageView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            catalog.learningResources(
                authentication,
                request,
                response,
                LearningResourceSearchCriteria(query, category, page, limit),
            ),
        )

    @GetMapping("/learning-resources/{resourceId}")
    fun learningResource(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable resourceId: UUID,
    ): ResponseEntity<LearningResourceView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(catalog.learningResource(authentication, request, response, resourceId))

    @GetMapping("/learning-resource-categories")
    fun learningResourceCategories(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<List<String>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(catalog.learningResourceCategories(authentication, request, response))

    @GetMapping("/editions")
    fun editions(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam @Size(min = 1, max = 50) editionId: List<UUID>,
    ): ResponseEntity<List<CatalogEditionView>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(catalog.editions(authentication, request, response, editionId))
}
