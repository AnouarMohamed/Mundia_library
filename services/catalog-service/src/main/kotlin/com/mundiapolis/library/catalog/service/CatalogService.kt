package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.dto.CatalogSearchFilters
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.CatalogReviewPage
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work

interface CatalogService {
    fun getWork(workId: String): Work?
    fun getEdition(editionId: String): Edition?
    fun getEditions(editionIds: List<String>): List<Edition>
    fun searchCatalog(filters: CatalogSearchFilters): CatalogSearchResult
    fun searchAdministrativeCatalog(filters: CatalogSearchFilters): CatalogSearchResult
    fun getDistinctGenres(): List<String>
    fun getPublishedReviews(workId: String, page: Int?, limit: Int?): CatalogReviewPage
}
