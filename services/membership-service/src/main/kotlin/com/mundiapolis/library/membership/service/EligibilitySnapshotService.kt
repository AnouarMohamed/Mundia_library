package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.EligibilitySnapshotPage
import com.mundiapolis.library.membership.dto.EligibilitySnapshotReceipt
import com.mundiapolis.library.membership.dto.InvalidMembershipCommandException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class EligibilitySnapshotService(
    private val store: EligibilitySnapshotStore,
    private val clock: Clock,
) {
    fun create(snapshotId: UUID, actorFingerprint: String): EligibilitySnapshotReceipt {
        validateActor(actorFingerprint)
        return store.create(snapshotId, actorFingerprint, clock.instant().truncatedTo(ChronoUnit.MICROS))
    }

    fun receipt(snapshotId: UUID, actorFingerprint: String): EligibilitySnapshotReceipt {
        validateActor(actorFingerprint)
        return store.receipt(snapshotId, actorFingerprint)
    }

    fun page(
        snapshotId: UUID,
        actorFingerprint: String,
        afterMemberId: UUID?,
        requestedLimit: Int?,
    ): EligibilitySnapshotPage {
        validateActor(actorFingerprint)
        val limit = requestedLimit ?: DEFAULT_PAGE_SIZE
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidMembershipCommandException("limit must be between 1 and $MAX_PAGE_SIZE")
        }
        return store.page(snapshotId, actorFingerprint, afterMemberId, limit)
    }

    private fun validateActor(actorFingerprint: String) {
        if (!SHA256.matches(actorFingerprint)) {
            throw InvalidMembershipCommandException("Snapshot actor fingerprint is invalid")
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 100
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
