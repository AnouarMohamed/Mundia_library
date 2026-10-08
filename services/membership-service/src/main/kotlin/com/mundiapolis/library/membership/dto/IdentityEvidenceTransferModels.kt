package com.mundiapolis.library.membership.dto

import java.time.Instant
import java.util.UUID

data class IdentityEvidenceTransferCommand(
    val transferId: UUID,
    val memberId: UUID,
    val evidenceId: UUID,
    val objectKey: String,
    val mimeType: String,
    val fileSize: Int,
    val checksumSha256: String,
    val sourceReferenceSha256: String,
    val scanAttestationSha256: String,
    val verifiedAt: Instant,
    val retentionExpiresAt: Instant,
    val actorFingerprint: String,
)

data class IdentityEvidenceTransferResult(
    val transferId: UUID,
    val evidenceId: UUID,
    val manifestSha256: String,
    val completedAt: Instant,
    val replayed: Boolean,
)

enum class IdentityEvidenceVerificationStatus {
    LEGACY_UNATTESTED,
    VERIFIED,
}

class IdentityEvidenceTransferNotFoundException :
    RuntimeException("Identity evidence transfer does not exist")
