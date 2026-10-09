package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.EligibilitySnapshotItem
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

object EligibilitySnapshotIntegrity {
    fun itemHash(item: EligibilitySnapshotItem): String = sha256(canonicalItem(item, false))

    fun sourceRevision(items: List<EligibilitySnapshotItem>): String = sha256(buildString {
        append(SOURCE_VERSION)
        items.sortedBy { it.memberId }.forEach { field(canonicalItem(it, true)) }
    })

    fun manifest(
        snapshotId: UUID,
        sourceRevision: String,
        items: List<EligibilitySnapshotItem>,
    ): String = sha256(buildString {
        append(MANIFEST_VERSION)
        field(snapshotId.toString())
        field(sourceRevision)
        items.sortedBy { it.memberId }.forEach { field(it.contentSha256) }
    })

    private fun canonicalItem(item: EligibilitySnapshotItem, includeContentHash: Boolean): String = buildString {
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

    private const val SOURCE_VERSION = "membership-eligibility-snapshot-source-v1"
    private const val MANIFEST_VERSION = "membership-eligibility-snapshot-manifest-v1"
    private const val ITEM_VERSION = "circulation-membership-eligibility-item-v1"
    private const val NULL_MARKER = "<null>"
}
