package com.mundiapolis.library.migration.eligibility

import java.time.Instant
import java.util.UUID

enum class EligibilityStatus {
    ELIGIBLE,
    INELIGIBLE,
    SUSPENDED,
}

data class SnapshotItem(
    val memberId: UUID,
    val status: EligibilityStatus,
    val reasonCode: String?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
    val contentSha256: String,
)

data class SnapshotReceipt(
    val snapshotId: UUID,
    val sourceRevision: String,
    val manifestSha256: String,
    val memberCount: Int,
    val createdAt: Instant,
    val replayed: Boolean,
)

data class SnapshotPage(
    val snapshotId: UUID,
    val items: List<SnapshotItem>,
    val nextAfterMemberId: UUID?,
)

data class BootstrapRequest(
    val sourceRevision: String,
    val items: List<SnapshotItem>,
)

data class BootstrapReceipt(
    val bootstrapId: UUID,
    val sourceRevision: String,
    val manifestSha256: String,
    val memberCount: Int,
    val completedAt: Instant,
    val replayed: Boolean,
)

data class ProjectedEligibility(
    val memberId: UUID,
    val status: EligibilityStatus,
    val reasonCode: String?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
)

data class BatchEvidence(
    val batchIndex: Int,
    val bootstrapId: UUID,
    val memberCount: Int,
    val manifestSha256: String,
    val applied: Boolean,
    val replayed: Boolean?,
)

data class ParityEvidence(
    val verifiedMemberCount: Int,
    val sourceRevision: String,
)

data class OperatorEvidence(
    val schemaVersion: Int = 2,
    val mode: String,
    val snapshotId: UUID,
    val sourceRevision: String,
    val sourceManifestSha256: String,
    val memberCount: Int,
    val batchSize: Int,
    val batches: List<BatchEvidence>,
    val parity: ParityEvidence?,
    val generatedAt: Instant,
)

data class OperatorCommand(
    val snapshotId: UUID,
    val batchSize: Int,
    val apply: Boolean,
)

interface MembershipSnapshotClient {
    fun create(snapshotId: UUID): SnapshotReceipt

    fun receipt(snapshotId: UUID): SnapshotReceipt

    fun page(snapshotId: UUID, afterMemberId: UUID?, limit: Int): SnapshotPage
}

interface CirculationBootstrapClient {
    fun bootstrap(bootstrapId: UUID, request: BootstrapRequest): BootstrapReceipt

    fun receipt(bootstrapId: UUID): BootstrapReceipt

    fun eligibility(memberId: UUID): ProjectedEligibility
}

class OperatorValidationException(message: String) : RuntimeException(message)

class RemoteServiceException(message: String) : RuntimeException(message)
