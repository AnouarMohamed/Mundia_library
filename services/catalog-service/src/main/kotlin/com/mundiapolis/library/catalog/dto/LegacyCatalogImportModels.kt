package com.mundiapolis.library.catalog.dto

import java.time.Instant
import java.util.UUID

data class LegacyCatalogImportItem(
    val workId: UUID,
    val editionId: UUID,
    val contributorId: UUID,
    val title: String,
    val author: String,
    val summary: String,
    val description: String,
    val genre: String,
    val rating: Double,
    val ratingCount: Int,
    val isbn: String,
    val publisher: String,
    val publicationYear: Int,
    val language: String,
    val pageCount: Int,
    val coverUrl: String?,
    val coverColor: String?,
    val videoUrl: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val reviews: List<LegacyCatalogReviewImportItem>,
    val contentSha256: String,
)

data class LegacyCatalogReviewImportItem(
    val reviewId: UUID,
    val memberId: UUID,
    val rating: Int,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class LegacyCatalogImportCommand(
    val importId: UUID,
    val sourceRevision: String,
    val items: List<LegacyCatalogImportItem>,
    val actorFingerprint: String,
)

data class LegacyCatalogImportResult(
    val importId: UUID,
    val sourceRevision: String,
    val manifestSha256: String,
    val workCount: Int,
    val editionCount: Int,
    val contributorCount: Int,
    val reviewCount: Int,
    val completedAt: Instant,
    val replayed: Boolean,
)
