package com.mundiapolis.library.bff.digitalcontent

import java.time.Instant
import java.util.UUID

enum class DigitalFormatView {
    PDF,
    EPUB,
}

data class DownloadableFormatView(
    val assetId: UUID,
    val format: DigitalFormatView,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseExpression: String,
    val attribution: String,
)

data class EditionDownloadAvailabilityView(
    val editionId: UUID,
    val downloadable: Boolean,
    val formats: List<DownloadableFormatView>,
)

data class DownloadAuthorizationView(
    val authorizationId: UUID,
    val assetId: UUID,
    val downloadUrl: String,
    val expiresAt: Instant,
)
