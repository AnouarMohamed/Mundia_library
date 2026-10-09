package com.mundiapolis.library.membership.adapter.outbound.persistence

import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_MEMBER
import com.mundiapolis.library.membership.dto.EligibilityProjectionStatus
import com.mundiapolis.library.membership.dto.EligibilitySnapshotConflictException
import com.mundiapolis.library.membership.dto.EligibilitySnapshotItem
import com.mundiapolis.library.membership.dto.EligibilitySnapshotNotFoundException
import com.mundiapolis.library.membership.dto.EligibilitySnapshotPage
import com.mundiapolis.library.membership.dto.EligibilitySnapshotReceipt
import com.mundiapolis.library.membership.dto.InvalidMembershipCommandException
import com.mundiapolis.library.membership.service.EligibilitySnapshotIntegrity
import com.mundiapolis.library.membership.service.EligibilitySnapshotStore
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqEligibilitySnapshotRepository(private val dsl: DSLContext) : EligibilitySnapshotStore {
    override fun create(
        snapshotId: UUID,
        actorFingerprint: String,
        createdAt: Instant,
    ): EligibilitySnapshotReceipt = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.execute("select pg_advisory_xact_lock(?)", lockKey(snapshotId))
        val existing = receiptRecord(tx, snapshotId)
        if (existing != null) {
            if (existing.get(ACTOR_FINGERPRINT)?.trim() != actorFingerprint) {
                throw EligibilitySnapshotConflictException()
            }
            return@transactionResult existing.toReceipt(snapshotId, replayed = true)
        }

        tx.execute("LOCK TABLE membership_member IN SHARE MODE")
        val memberCount = tx.fetchCount(MEMBERSHIP_MEMBER)
        if (memberCount !in 1..MAX_SNAPSHOT_MEMBERS) {
            throw InvalidMembershipCommandException(
                "Eligibility snapshot must contain between 1 and $MAX_SNAPSHOT_MEMBERS members",
            )
        }
        val items = tx.select(
            MEMBERSHIP_MEMBER.MEMBER_ID,
            MEMBERSHIP_MEMBER.ACCOUNT_STATUS,
            MEMBERSHIP_MEMBER.MAX_ACTIVE_LOANS,
            MEMBERSHIP_MEMBER.CURRENT_ACTIVE_LOANS,
            MEMBERSHIP_MEMBER.HAS_UNPAID_OVERDUE_FINES,
            MEMBERSHIP_MEMBER.AGGREGATE_VERSION,
            MEMBERSHIP_MEMBER.UPDATED_AT,
        ).from(MEMBERSHIP_MEMBER)
            .orderBy(MEMBERSHIP_MEMBER.MEMBER_ID.asc())
            .fetch(::snapshotItem)
        val sourceRevision = EligibilitySnapshotIntegrity.sourceRevision(items)
        val manifest = EligibilitySnapshotIntegrity.manifest(snapshotId, sourceRevision, items)

        tx.insertInto(SNAPSHOT_TABLE)
            .columns(
                SNAPSHOT_ID,
                SOURCE_REVISION,
                MANIFEST_SHA256,
                ACTOR_FINGERPRINT,
                MEMBER_COUNT,
                CREATED_AT,
            )
            .values(
                snapshotId,
                sourceRevision,
                manifest,
                actorFingerprint,
                items.size,
                createdAt.utc(),
            )
            .execute()
        tx.batch(
            items.map { item ->
                tx.insertInto(ITEM_TABLE)
                    .columns(
                        SNAPSHOT_ID,
                        MEMBER_ID,
                        ELIGIBILITY_STATUS,
                        REASON_CODE,
                        SOURCE_VERSION,
                        SOURCE_OCCURRED_AT,
                        CONTENT_SHA256,
                    )
                    .values(
                        snapshotId,
                        item.memberId,
                        item.status.name,
                        item.reasonCode,
                        item.sourceVersion,
                        item.sourceOccurredAt.utc(),
                        item.contentSha256,
                    )
            },
        ).execute()
        EligibilitySnapshotReceipt(
            snapshotId,
            sourceRevision,
            manifest,
            items.size,
            createdAt,
            replayed = false,
        )
    }

    override fun receipt(snapshotId: UUID, actorFingerprint: String): EligibilitySnapshotReceipt =
        receiptRecord(dsl, snapshotId, actorFingerprint)?.toReceipt(snapshotId, replayed = true)
            ?: throw EligibilitySnapshotNotFoundException()

    override fun page(
        snapshotId: UUID,
        actorFingerprint: String,
        afterMemberId: UUID?,
        limit: Int,
    ): EligibilitySnapshotPage {
        if (receiptRecord(dsl, snapshotId, actorFingerprint) == null) {
            throw EligibilitySnapshotNotFoundException()
        }
        var condition = SNAPSHOT_ID.eq(snapshotId)
        if (afterMemberId != null) condition = condition.and(MEMBER_ID.gt(afterMemberId))
        val fetched = dsl.select(
            MEMBER_ID,
            ELIGIBILITY_STATUS,
            REASON_CODE,
            SOURCE_VERSION,
            SOURCE_OCCURRED_AT,
            CONTENT_SHA256,
        ).from(ITEM_TABLE)
            .where(condition)
            .orderBy(MEMBER_ID.asc())
            .limit(limit + 1)
            .fetch { record -> record.toItem() }
        val items = fetched.take(limit)
        return EligibilitySnapshotPage(
            snapshotId,
            items,
            if (fetched.size > limit) items.last().memberId else null,
        )
    }

    private fun snapshotItem(record: Record): EligibilitySnapshotItem {
        val accountStatus = requireNotNull(record.get(MEMBERSHIP_MEMBER.ACCOUNT_STATUS))
        val maximum = requireNotNull(record.get(MEMBERSHIP_MEMBER.MAX_ACTIVE_LOANS))
        val current = requireNotNull(record.get(MEMBERSHIP_MEMBER.CURRENT_ACTIVE_LOANS))
        val hasFines = requireNotNull(record.get(MEMBERSHIP_MEMBER.HAS_UNPAID_OVERDUE_FINES))
        val reason = when {
            accountStatus != "APPROVED" -> "ACCOUNT_NOT_APPROVED"
            current >= maximum -> "ACTIVE_LOAN_LIMIT_REACHED"
            hasFines -> "UNPAID_OVERDUE_FINES"
            else -> null
        }
        val item = EligibilitySnapshotItem(
            memberId = requireNotNull(record.get(MEMBERSHIP_MEMBER.MEMBER_ID)),
            status = if (reason == null) {
                EligibilityProjectionStatus.ELIGIBLE
            } else {
                EligibilityProjectionStatus.INELIGIBLE
            },
            reasonCode = reason,
            sourceVersion = requireNotNull(record.get(MEMBERSHIP_MEMBER.AGGREGATE_VERSION)),
            sourceOccurredAt = requireNotNull(record.get(MEMBERSHIP_MEMBER.UPDATED_AT)).toInstant(),
            contentSha256 = "",
        )
        return item.copy(contentSha256 = EligibilitySnapshotIntegrity.itemHash(item))
    }

    private fun Record.toItem() = EligibilitySnapshotItem(
        memberId = requireNotNull(get(MEMBER_ID)),
        status = EligibilityProjectionStatus.valueOf(requireNotNull(get(ELIGIBILITY_STATUS))),
        reasonCode = get(REASON_CODE),
        sourceVersion = requireNotNull(get(SOURCE_VERSION)),
        sourceOccurredAt = requireNotNull(get(SOURCE_OCCURRED_AT)).toInstant(),
        contentSha256 = requireNotNull(get(CONTENT_SHA256)).trim(),
    )

    private fun receiptRecord(
        context: DSLContext,
        snapshotId: UUID,
        actorFingerprint: String? = null,
    ): Record? {
        var condition = SNAPSHOT_ID.eq(snapshotId)
        if (actorFingerprint != null) condition = condition.and(ACTOR_FINGERPRINT.eq(actorFingerprint))
        return context.select(
            SOURCE_REVISION,
            MANIFEST_SHA256,
            ACTOR_FINGERPRINT,
            MEMBER_COUNT,
            CREATED_AT,
        ).from(SNAPSHOT_TABLE).where(condition).fetchOne()
    }

    private fun Record.toReceipt(snapshotId: UUID, replayed: Boolean) = EligibilitySnapshotReceipt(
        snapshotId,
        requireNotNull(get(SOURCE_REVISION)).trim(),
        requireNotNull(get(MANIFEST_SHA256)).trim(),
        requireNotNull(get(MEMBER_COUNT)),
        requireNotNull(get(CREATED_AT)).toInstant(),
        replayed,
    )

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val MAX_SNAPSHOT_MEMBERS = 10_000
        fun lockKey(id: UUID): Long = id.mostSignificantBits xor id.leastSignificantBits
        fun table(name: String): Table<Record> = DSL.table(DSL.name(name))
        fun <T> field(name: String, type: Class<T>): Field<T> = DSL.field(DSL.name(name), type)
        val SNAPSHOT_TABLE = table("membership_eligibility_snapshot")
        val ITEM_TABLE = table("membership_eligibility_snapshot_item")
        val SNAPSHOT_ID = field("snapshot_id", UUID::class.java)
        val SOURCE_REVISION = field("source_revision", String::class.java)
        val MANIFEST_SHA256 = field("manifest_sha256", String::class.java)
        val ACTOR_FINGERPRINT = field("actor_fingerprint", String::class.java)
        val MEMBER_COUNT = field("member_count", Int::class.java)
        val CREATED_AT = field("created_at", OffsetDateTime::class.java)
        val MEMBER_ID = field("member_id", UUID::class.java)
        val ELIGIBILITY_STATUS = field("eligibility_status", String::class.java)
        val REASON_CODE = field("reason_code", String::class.java)
        val SOURCE_VERSION = field("source_version", Long::class.java)
        val SOURCE_OCCURRED_AT = field("source_occurred_at", OffsetDateTime::class.java)
        val CONTENT_SHA256 = field("content_sha256", String::class.java)
    }
}
