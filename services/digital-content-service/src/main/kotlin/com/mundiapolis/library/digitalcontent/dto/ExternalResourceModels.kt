package com.mundiapolis.library.digitalcontent.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class RegisterExternalResourceRequest(
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    val sourceProvider: String,
    @field:NotBlank
    @field:Size(max = 2048)
    val sourceUrl: String,
    @field:NotBlank
    @field:Size(max = 2048)
    val downloadUrl: String,
    @field:NotBlank
    @field:Size(max = 100)
    val mediaType: String,
    @field:NotBlank
    @field:Size(max = 32)
    val licenseExpression: String,
    @field:NotBlank
    @field:Size(max = 2048)
    val licenseUrl: String,
    @field:NotBlank
    @field:Size(min = 2, max = 1000)
    val attribution: String,
)

enum class ExternalMediaType(val value: String) {
    PDF("application/pdf"),
    EPUB("application/epub+zip"),
}

enum class ExternalLicenseExpression(val value: String, val canonicalUrl: String) {
    CC_BY_4_0("CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/"),
    CC_BY_SA_4_0("CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/"),
    CC0_1_0("CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/"),
    PDM_1_0("PDM-1.0", "https://creativecommons.org/publicdomain/mark/1.0/"),
}

data class ExternalResourceRegistration(
    val resourceId: UUID,
    val sourceProvider: String,
    val licenseExpression: String,
    val replayed: Boolean,
)

data class ExternalResourceAvailability(
    val resourceId: UUID,
    val downloadable: Boolean,
    val sourceProvider: String?,
    val mediaType: String?,
    val licenseExpression: String?,
)

data class ExternalDownloadAuthorization(
    val authorizationId: UUID,
    val resourceId: UUID,
    val sourceProvider: String,
    val licenseExpression: String,
    val downloadUrl: String,
)
