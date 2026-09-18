package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.dto.Author
import com.mundiapolis.library.catalog.dto.CatalogSearchFilters
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work
import kotlinx.coroutines.flow.Flow

interface CatalogService {
    suspend fun getEdition(editionId: String): Edition?
    suspend fun searchCatalog(filters: CatalogSearchFilters): CatalogSearchResult
    suspend fun getDistinctGenres(): List<String>
}
