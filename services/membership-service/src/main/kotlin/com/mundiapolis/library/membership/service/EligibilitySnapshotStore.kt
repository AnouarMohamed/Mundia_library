package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.EligibilitySnapshotPage
import com.mundiapolis.library.membership.dto.EligibilitySnapshotReceipt
import java.time.Instant
import java.util.UUID

interface EligibilitySnapshotStore {
    fun create(snapshotId: UUID, actorFingerprint: String, createdAt: Instant): EligibilitySnapshotReceipt

    fun receipt(snapshotId: UUID, actorFingerprint: String): EligibilitySnapshotReceipt

    fun page(
        snapshotId: UUID,
        actorFingerprint: String,
        afterMemberId: UUID?,
        limit: Int,
    ): EligibilitySnapshotPage
}
