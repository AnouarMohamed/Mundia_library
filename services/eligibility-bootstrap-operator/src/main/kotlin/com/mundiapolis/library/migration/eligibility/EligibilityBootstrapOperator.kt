package com.mundiapolis.library.migration.eligibility

import java.time.Clock

class EligibilityBootstrapOperator(
    private val membership: MembershipSnapshotClient,
    private val circulation: CirculationBootstrapClient?,
    private val clock: Clock,
) {
    fun execute(command: OperatorCommand): OperatorEvidence {
        if (command.batchSize !in 1..MAX_BATCH_SIZE) {
            throw OperatorValidationException("Batch size must be between 1 and $MAX_BATCH_SIZE")
        }
        if (command.apply && circulation == null) {
            throw OperatorValidationException("Circulation client is required in apply mode")
        }

        val created = membership.create(command.snapshotId)
        val receipt = membership.receipt(command.snapshotId)
        requireSameSnapshot(created, receipt)
        if (receipt.snapshotId != command.snapshotId) {
            throw OperatorValidationException("Membership snapshot receipt identity is invalid")
        }
        if (receipt.memberCount !in 1..MAX_SNAPSHOT_MEMBERS) {
            throw OperatorValidationException("Snapshot member count is outside the supported range")
        }
        val items = readAll(command, receipt)
        verifySnapshot(receipt, items)

        val batches = items.chunked(command.batchSize).mapIndexed { index, batch ->
            val bootstrapId = EligibilityIntegrity.bootstrapId(command.snapshotId, index)
            val expectedManifest = EligibilityIntegrity.bootstrapManifest(
                bootstrapId,
                receipt.sourceRevision,
                batch,
            )
            if (!command.apply) {
                BatchEvidence(index, bootstrapId, batch.size, expectedManifest, applied = false, replayed = null)
            } else {
                applyBatch(index, bootstrapId, receipt.sourceRevision, batch, expectedManifest)
            }
        }
        val parity = if (command.apply) verifyTargetParity(items, receipt.sourceRevision) else null
        return OperatorEvidence(
            mode = if (command.apply) "APPLY" else "DRY_RUN",
            snapshotId = command.snapshotId,
            sourceRevision = receipt.sourceRevision,
            sourceManifestSha256 = receipt.manifestSha256,
            memberCount = receipt.memberCount,
            batchSize = command.batchSize,
            batches = batches,
            parity = parity,
            generatedAt = clock.instant(),
        )
    }

    private fun verifyTargetParity(items: List<SnapshotItem>, sourceRevision: String): ParityEvidence {
        val target = requireNotNull(circulation)
        val observed = items.map { expected ->
            val projection = target.eligibility(expected.memberId)
            val unsigned = SnapshotItem(
                projection.memberId,
                projection.status,
                projection.reasonCode,
                projection.sourceVersion,
                projection.sourceOccurredAt,
                "",
            )
            val actual = unsigned.copy(contentSha256 = EligibilityIntegrity.itemHash(unsigned))
            if (actual != expected) {
                throw OperatorValidationException("Circulation eligibility projection differs from the source snapshot")
            }
            actual
        }
        val observedRevision = EligibilityIntegrity.sourceRevision(observed)
        if (observedRevision != sourceRevision) {
            throw OperatorValidationException("Circulation eligibility projection revision is invalid")
        }
        return ParityEvidence(observed.size, observedRevision)
    }

    private fun readAll(command: OperatorCommand, receipt: SnapshotReceipt): List<SnapshotItem> {
        val items = mutableListOf<SnapshotItem>()
        var cursor: java.util.UUID? = null
        do {
            val page = membership.page(command.snapshotId, cursor, MAX_PAGE_SIZE)
            if (page.snapshotId != command.snapshotId || page.items.isEmpty()) {
                throw OperatorValidationException("Snapshot page identity or cardinality is invalid")
            }
            page.items.forEach { item ->
                EligibilityIntegrity.validateItem(item)
                if (items.lastOrNull()?.memberId?.let { item.memberId <= it } == true) {
                    throw OperatorValidationException("Snapshot pages are not strictly ordered")
                }
                items += item
            }
            if (items.size > receipt.memberCount) {
                throw OperatorValidationException("Snapshot returned more members than its receipt")
            }
            val next = page.nextAfterMemberId
            if (next != null && next != page.items.last().memberId) {
                throw OperatorValidationException("Snapshot page cursor is invalid")
            }
            cursor = next
        } while (cursor != null)
        return items
    }

    private fun verifySnapshot(receipt: SnapshotReceipt, items: List<SnapshotItem>) {
        if (items.size != receipt.memberCount) {
            throw OperatorValidationException("Snapshot member count does not match its receipt")
        }
        val sourceRevision = EligibilityIntegrity.sourceRevision(items)
        if (sourceRevision != receipt.sourceRevision) {
            throw OperatorValidationException("Snapshot source revision is invalid")
        }
        if (EligibilityIntegrity.sourceManifest(receipt.snapshotId, sourceRevision, items) != receipt.manifestSha256) {
            throw OperatorValidationException("Snapshot manifest is invalid")
        }
    }

    private fun applyBatch(
        index: Int,
        bootstrapId: java.util.UUID,
        sourceRevision: String,
        items: List<SnapshotItem>,
        expectedManifest: String,
    ): BatchEvidence {
        val target = requireNotNull(circulation)
        val applied = target.bootstrap(bootstrapId, BootstrapRequest(sourceRevision, items))
        val stored = target.receipt(bootstrapId)
        requireSameBootstrap(applied, stored)
        if (
            stored.bootstrapId != bootstrapId ||
            stored.sourceRevision != sourceRevision ||
            stored.memberCount != items.size ||
            stored.manifestSha256 != expectedManifest
        ) throw OperatorValidationException("Circulation bootstrap receipt does not match the verified batch")
        return BatchEvidence(index, bootstrapId, items.size, expectedManifest, applied = true, replayed = applied.replayed)
    }

    private fun requireSameSnapshot(first: SnapshotReceipt, second: SnapshotReceipt) {
        if (first.copy(replayed = false) != second.copy(replayed = false)) {
            throw OperatorValidationException("Membership snapshot PUT and GET receipts differ")
        }
    }

    private fun requireSameBootstrap(first: BootstrapReceipt, second: BootstrapReceipt) {
        if (first.copy(replayed = false) != second.copy(replayed = false)) {
            throw OperatorValidationException("Circulation bootstrap PUT and GET receipts differ")
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
        const val MAX_PAGE_SIZE = 100
        const val MAX_SNAPSHOT_MEMBERS = 10_000
    }
}
