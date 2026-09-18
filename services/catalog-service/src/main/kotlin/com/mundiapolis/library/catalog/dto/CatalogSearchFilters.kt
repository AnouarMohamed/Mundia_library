package com.mundiapolis.library.catalog.dto

data class CatalogSearchFilters(
    val query: String? = null,
    val genre: String? = null,
    val authorId: String? = null,
    val availableOnly: Boolean? = null,
    val minRating: Double? = null,
    val sortBy: String? = null,
    val page: Int? = null,
    val limit: Int? = null
)
