package com.mundiapolis.library.digitalcontent.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateIngestionRequest(
    val editionId: UUID,
    val format: DigitalFormat,
    @field:Min(1)
    @field:Max(1_073_741_824)
    val sizeBytes: Long,
    @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val sha256: String,
    @field:Size(min = 2, max = 100)
    val sourceProvider: String,
    @field:Size(min = 9, max = 2048)
    val sourceUri: String,
    @field:Pattern(regexp = "^(CC-BY-4[.]0|CC-BY-SA-4[.]0|CC0-1[.]0|PDM-1[.]0)$")
    val licenseExpression: String,
    @field:Size(min = 2, max = 1000)
    val attribution: String,
    val rightsExpiresAt: Instant?,
)

data class IngestionUploadGrant(
    val ingestionId: UUID,
    val state: String,
    val uploadUrl: String,
    val method: String,
    val requiredHeaders: Map<String, String>,
    val uploadExpiresAt: Instant,
    val sessionExpiresAt: Instant,
    val replayed: Boolean,
)
