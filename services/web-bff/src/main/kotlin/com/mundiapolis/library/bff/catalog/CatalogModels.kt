package com.mundiapolis.library.bff.catalog

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
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
    val aggregateVersion: Long,
)

data class CatalogAuthorView(val id: UUID, val name: String, val bio: String?)

data class CatalogWorkView(
    val workId: UUID,
    val title: String,
    val summary: String,
    val description: String,
    val genre: String,
    val rating: Double,
    val authors: List<CatalogAuthorView>,
    val aggregateVersion: Long,
)

data class CatalogAuthorCommandView(
    val contributorId: UUID,
    @field:Size(min = 1, max = 300) val name: String,
    @field:Size(max = 5_000) val bio: String?,
)

data class CreateCatalogWorkView(
    val workId: UUID,
    @field:Size(min = 1, max = 500) val title: String,
    @field:Size(max = 1_000) val summary: String,
    @field:Size(max = 10_000) val description: String,
    @field:Size(min = 1, max = 120) val genre: String,
    @field:Valid @field:Size(min = 1, max = 20) val authors: List<CatalogAuthorCommandView>,
    @field:Size(min = 8, max = 500) val reason: String,
)

data class UpdateCatalogWorkView(
    @field:Size(min = 1, max = 500) val title: String,
    @field:Size(max = 1_000) val summary: String,
    @field:Size(max = 10_000) val description: String,
    @field:Size(min = 1, max = 120) val genre: String,
    @field:Valid @field:Size(min = 1, max = 20) val authors: List<CatalogAuthorCommandView>,
    @field:Size(min = 8, max = 500) val reason: String,
)

data class CreateCatalogEditionView(
    val editionId: UUID,
    @field:Size(min = 1, max = 500) val title: String,
    @field:Size(min = 1, max = 32) val isbn: String,
    @field:Size(min = 1, max = 300) val publisher: String,
    @field:Min(1000) @field:Max(3000) val publicationYear: Int,
    @field:Size(min = 1, max = 80) val language: String,
    @field:Min(1) @field:Max(100_000) val pageCount: Int,
    @field:Size(max = 2_048) val coverUrl: String?,
    @field:Pattern(regexp = "^#[0-9A-Fa-f]{6}$") val coverColor: String?,
    @field:Size(max = 2_048) val videoUrl: String?,
    val isActive: Boolean,
    @field:Size(min = 8, max = 500) val reason: String,
)

data class UpdateCatalogEditionView(
    @field:Size(min = 1, max = 500) val title: String,
    @field:Size(min = 1, max = 32) val isbn: String,
    @field:Size(min = 1, max = 300) val publisher: String,
    @field:Min(1000) @field:Max(3000) val publicationYear: Int,
    @field:Size(min = 1, max = 80) val language: String,
    @field:Min(1) @field:Max(100_000) val pageCount: Int,
    @field:Size(max = 2_048) val coverUrl: String?,
    @field:Pattern(regexp = "^#[0-9A-Fa-f]{6}$") val coverColor: String?,
    @field:Size(max = 2_048) val videoUrl: String?,
    @field:Size(min = 8, max = 500) val reason: String,
)

data class SetCatalogEditionActiveView(
    val isActive: Boolean,
    @field:Size(min = 8, max = 500) val reason: String,
)

data class CatalogCommandView(
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val occurredAt: Instant,
)

data class CatalogMutationResult(val command: CatalogCommandView, val replayed: Boolean)

data class LearningResourceSearchCriteria(
    val query: String?,
    val category: String?,
    val page: Int?,
    val limit: Int?,
)

data class LearningResourcePageView(
    val resources: List<LearningResourceView>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

data class LearningResourceView(
    val resourceId: UUID,
    val title: String,
    val author: String?,
    val description: String?,
    val category: String,
    val language: String,
    val coverUrl: String?,
    val coverAlt: String?,
    val sourceName: String,
    val sourceUrl: String,
    val licenseExpression: String,
    val licenseUrl: String,
    val accessMode: LearningResourceAccessMode,
    val readUrl: String?,
)

enum class LearningResourceAccessMode { DOWNLOAD, READ_AT_SOURCE }
