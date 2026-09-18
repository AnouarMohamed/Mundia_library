package com.mundiapolis.library.catalog.dto

data class CatalogSearchResult(
    val editions: List<Edition>,
    val total: Int,
    val page: Int,
    val totalPages: Int
)
