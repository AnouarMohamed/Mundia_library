package com.mundiapolis.library.catalog.dto

import java.time.Instant

data class CatalogReview(
    val reviewId: String,
    val workId: String,
    val rating: Int,
    val content: String,
    val reviewerLabel: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CatalogReviewPage(
    val reviews: List<CatalogReview>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)
