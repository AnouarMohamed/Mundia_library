package com.mundiapolis.library.catalog.dto

import java.time.Instant
import java.util.UUID

data class LearningResource(
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
    val accessMode: String,
    val readUrl: String?,
)

data class LearningResourcePage(
    val resources: List<LearningResource>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

data class LearningResourceImportItem(
    val resourceId: UUID,
    val sourceRecordKey: String,
    val title: String,
    val author: String?,
    val description: String?,
    val category: String,
    val language: String,
    val coverUrl: String?,
    val coverAlt: String?,
    val sourceUrl: String,
    val licenseExpression: String,
    val licenseUrl: String,
    val accessMode: String,
    val readUrl: String?,
    val contentSha256: String,
)

data class LearningResourceImportCommand(
    val importId: UUID,
    val sourceName: String,
    val sourceRevision: String,
    val items: List<LearningResourceImportItem>,
    val actorFingerprint: String,
)

data class LearningResourceImportResult(
    val importId: UUID,
    val sourceName: String,
    val sourceRevision: String,
    val manifestSha256: String,
    val recordCount: Int,
    val insertedCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int,
    val completedAt: Instant,
    val replayed: Boolean,
)
