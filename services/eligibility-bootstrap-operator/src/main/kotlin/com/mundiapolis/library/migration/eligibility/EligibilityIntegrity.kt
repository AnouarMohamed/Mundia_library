package com.mundiapolis.library.migration.eligibility

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

object EligibilityIntegrity {
    fun itemHash(item: SnapshotItem): String = sha256(canonicalItem(item, includeContentHash = false))

    fun sourceRevision(items: List<SnapshotItem>): String = sha256(buildString {
        append(SOURCE_VERSION)
        items.sortedBy { it.memberId }.forEach { field(canonicalItem(it, includeContentHash = true)) }
    })

    fun sourceManifest(snapshotId: UUID, sourceRevision: String, items: List<SnapshotItem>): String =
        sha256(buildString {
            append(SOURCE_MANIFEST_VERSION)
            field(snapshotId.toString())
            field(sourceRevision)
            items.sortedBy { it.memberId }.forEach { field(it.contentSha256) }
        })

    fun bootstrapManifest(bootstrapId: UUID, sourceRevision: String, items: List<SnapshotItem>): String =
        sha256(buildString {
            append(BOOTSTRAP_MANIFEST_VERSION)
            field(bootstrapId.toString())
            field(sourceRevision)
            items.sortedBy { it.memberId }.forEach { field(canonicalItem(it, includeContentHash = true)) }
        })

    fun bootstrapId(snapshotId: UUID, batchIndex: Int): UUID {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$BATCH_ID_VERSION\u001f$snapshotId\u001f$batchIndex".toByteArray(StandardCharsets.UTF_8),
        ).copyOf(16)
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long)
    }

    fun validateItem(item: SnapshotItem) {
        if (item.sourceVersion < 0) throw OperatorValidationException("Snapshot source version is negative")
        if (
            (item.reasonCode != null && !REASON.matches(item.reasonCode)) ||
            (item.status == EligibilityStatus.ELIGIBLE) != (item.reasonCode == null)
        ) {
            throw OperatorValidationException("Snapshot eligibility status and reason are inconsistent")
        }
        if (!SHA256.matches(item.contentSha256) || itemHash(item) != item.contentSha256) {
            throw OperatorValidationException("Snapshot item digest is invalid")
        }
    }

    private fun canonicalItem(item: SnapshotItem, includeContentHash: Boolean): String = buildString {
        append(ITEM_VERSION)
        field(item.memberId.toString())
        field(item.status.name)
        field(item.reasonCode ?: NULL_MARKER)
        field(item.sourceVersion.toString())
        field(item.sourceOccurredAt.toString())
        if (includeContentHash) field(item.contentSha256)
    }

    private fun StringBuilder.field(value: String) {
        append('\u001f').append(value.length).append(':').append(value)
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private const val ITEM_VERSION = "circulation-membership-eligibility-item-v1"
    private const val SOURCE_VERSION = "membership-eligibility-snapshot-source-v1"
    private const val SOURCE_MANIFEST_VERSION = "membership-eligibility-snapshot-manifest-v1"
    private const val BOOTSTRAP_MANIFEST_VERSION = "circulation-membership-eligibility-bootstrap-v1"
    private const val BATCH_ID_VERSION = "eligibility-bootstrap-batch-id-v1"
    private const val NULL_MARKER = "<null>"
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val REASON = Regex("^[A-Z][A-Z0-9_]{0,63}$")
}
