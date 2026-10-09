package com.mundiapolis.library.membership.dto

import java.time.Instant
import java.util.UUID

enum class EligibilityProjectionStatus {
    ELIGIBLE,
    INELIGIBLE,
    SUSPENDED,
}

data class EligibilitySnapshotItem(
    val memberId: UUID,
    val status: EligibilityProjectionStatus,
    val reasonCode: String?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
    val contentSha256: String,
)

data class EligibilitySnapshotReceipt(
    val snapshotId: UUID,
    val sourceRevision: String,
    val manifestSha256: String,
    val memberCount: Int,
    val createdAt: Instant,
    val replayed: Boolean,
)

data class EligibilitySnapshotPage(
    val snapshotId: UUID,
    val items: List<EligibilitySnapshotItem>,
    val nextAfterMemberId: UUID?,
)

class EligibilitySnapshotNotFoundException :
    RuntimeException("Membership eligibility snapshot does not exist")

class EligibilitySnapshotConflictException :
    RuntimeException("Snapshot ID is bound to another actor")
