package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_POLICY_IDEMPOTENCY
import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.StoredPolicyIdempotencyResult
import com.mundiapolis.library.circulation.application.port.outbound.PolicyIdempotencyStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqPolicyIdempotencyStore(
    private val dsl: DSLContext,
) : PolicyIdempotencyStore {
    override fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean = dsl.insertInto(CIRCULATION_POLICY_IDEMPOTENCY)
        .set(CIRCULATION_POLICY_IDEMPOTENCY.OWNER_FINGERPRINT, owner.fingerprint)
        .set(CIRCULATION_POLICY_IDEMPOTENCY.IDEMPOTENCY_KEY, key.value)
        .set(CIRCULATION_POLICY_IDEMPOTENCY.REQUEST_FINGERPRINT, requestFingerprint)
        .set(CIRCULATION_POLICY_IDEMPOTENCY.CREATED_AT, createdAt.toOffsetDateTime())
        .set(CIRCULATION_POLICY_IDEMPOTENCY.EXPIRES_AT, expiresAt.toOffsetDateTime())
        .onConflictDoNothing()
        .execute() == 1

    override fun find(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
    ): StoredPolicyIdempotencyResult? = dsl.selectFrom(CIRCULATION_POLICY_IDEMPOTENCY)
        .where(
            CIRCULATION_POLICY_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                .and(CIRCULATION_POLICY_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value)),
        )
        .fetchOne()
        ?.let {
            StoredPolicyIdempotencyResult(
                requestFingerprint = requireNotNull(it.requestFingerprint),
                revisionId = it.revisionId,
            )
        }

    override fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        revisionId: UUID,
        completedAt: Instant,
    ) {
        val updated = dsl.update(CIRCULATION_POLICY_IDEMPOTENCY)
            .set(CIRCULATION_POLICY_IDEMPOTENCY.REVISION_ID, revisionId)
            .set(CIRCULATION_POLICY_IDEMPOTENCY.COMPLETED_AT, completedAt.toOffsetDateTime())
            .where(
                CIRCULATION_POLICY_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                    .and(CIRCULATION_POLICY_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value))
                    .and(CIRCULATION_POLICY_IDEMPOTENCY.COMPLETED_AT.isNull),
            )
            .execute()
        if (updated != 1) {
            throw ConcurrentCirculationUpdateException()
        }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
