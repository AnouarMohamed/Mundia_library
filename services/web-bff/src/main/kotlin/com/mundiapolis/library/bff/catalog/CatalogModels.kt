package com.mundiapolis.library.bff.catalog

import java.util.UUID

data class CatalogSearchCriteria(
    val query: String?,
    val genre: String?,
    val authorId: UUID?,
    val availableOnly: Boolean?,
    val minRating: Double?,
    val sortBy: String?,
    val page: Int?,
    val limit: Int?,
)

data class CatalogSearchView(
    val editions: List<CatalogEditionView>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

data class CatalogEditionView(
    val editionId: UUID,
    val workId: UUID,
    val title: String,
    val isbn: String,
    val publisher: String,
    val publicationYear: Int,
    val language: String,
    val pageCount: Int,
    val coverUrl: String?,
    val coverColor: String?,
    val videoUrl: String?,
    val totalCopies: Int,
    val availableCopies: Int,
    val isActive: Boolean,
)
