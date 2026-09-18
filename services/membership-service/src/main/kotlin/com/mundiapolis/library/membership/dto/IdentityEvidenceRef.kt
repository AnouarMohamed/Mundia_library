package com.mundiapolis.library.membership.dto

import java.time.Instant

data class IdentityEvidenceRef(
    val evidenceId: String,
    val memberId: String,
    val mimeType: String,
    val fileSize: Int,
    val checksumSha256: String,
    val uploadedAt: Instant,
    val signedReadUrl: String? = null
)
