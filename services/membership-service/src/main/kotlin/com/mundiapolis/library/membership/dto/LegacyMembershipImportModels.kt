package com.mundiapolis.library.membership.dto

import java.time.Instant
import java.util.UUID

data class LegacyMembershipImportItem(
    val memberId: UUID,
    val email: String,
    val fullName: String,
    val universityId: Int,
    val status: AccountStatus,
    val role: MembershipRole,
    val maxActiveLoans: Int,
    val currentActiveLoans: Int,
    val hasUnpaidOverdueFines: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val evidenceReferenceSha256: String,
    val contentSha256: String,
)

data class LegacyMembershipImportCommand(
    val importId: UUID,
    val sourceRevision: String,
    val items: List<LegacyMembershipImportItem>,
    val actorFingerprint: String,
)

data class LegacyMembershipImportResult(
    val importId: UUID,
    val sourceRevision: String,
    val manifestSha256: String,
    val memberCount: Int,
    val quarantinedEvidenceCount: Int,
    val completedAt: Instant,
    val replayed: Boolean,
)
