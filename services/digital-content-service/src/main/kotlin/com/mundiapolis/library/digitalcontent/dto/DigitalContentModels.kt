package com.mundiapolis.library.digitalcontent.dto

import java.util.UUID

enum class DigitalFormat {
    PDF,
    EPUB,
}

data class DownloadableFormat(
    val assetId: UUID,
    val format: DigitalFormat,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseExpression: String,
    val attribution: String,
)

data class EditionDownloadAvailability(
    val editionId: UUID,
    val downloadable: Boolean,
    val formats: List<DownloadableFormat>,
)
