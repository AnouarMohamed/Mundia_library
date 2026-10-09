package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapCommand
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapConflictException
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapItem
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapNotFoundException
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapResult
import com.mundiapolis.library.circulation.application.port.outbound.MembershipEligibilityBootstrapStore
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
class JooqMembershipEligibilityBootstrapRepository(private val dsl: DSLContext) :
    MembershipEligibilityBootstrapStore {
    override fun bootstrap(
        command: MembershipEligibilityBootstrapCommand,
        manifestSha256: String,
        completedAt: Instant,
    ): MembershipEligibilityBootstrapResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.execute("select pg_advisory_xact_lock(?)", lockKey(command.bootstrapId))
        val existingReceipt = receipt(tx, command.bootstrapId)
        if (existingReceipt != null) {
            if (
                existingReceipt.get(MANIFEST_SHA256)?.trim() != manifestSha256 ||
                existingReceipt.get(ACTOR_FINGERPRINT)?.trim() != command.actorFingerprint
            ) throw MembershipEligibilityBootstrapConflictException(
                "Bootstrap ID is bound to different input or actor",
            )
            return@transactionResult existingReceipt.toResult(command.bootstrapId, replayed = true)
        }

        command.items.sortedBy { it.memberId.value }.forEach { item ->
            tx.fetch(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                item.memberId.value.toString(),
            )
            reconcileProjection(tx, item, completedAt)
        }

        tx.insertInto(BOOTSTRAP_TABLE)
            .columns(
                BOOTSTRAP_ID, SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT,
                MEMBER_COUNT, COMPLETED_AT,
            )
            .values(
                command.bootstrapId, command.sourceRevision, manifestSha256,
                command.actorFingerprint, command.items.size, completedAt.utc(),
            )
            .execute()
        MembershipEligibilityBootstrapResult(
            command.bootstrapId,
            command.sourceRevision,
            manifestSha256,
            command.items.size,
            completedAt,
            replayed = false,
        )
    }

    override fun receipt(bootstrapId: UUID): MembershipEligibilityBootstrapResult =
        receipt(dsl, bootstrapId)?.toResult(bootstrapId, replayed = true)
            ?: throw MembershipEligibilityBootstrapNotFoundException()

    private fun reconcileProjection(
        tx: DSLContext,
        item: MembershipEligibilityBootstrapItem,
        completedAt: Instant,
    ) {
        val existing = tx.select(
            MEMBER_ID, STATUS, REASON_CODE, SOURCE_VERSION, SOURCE_OCCURRED_AT,
        ).from(PROJECTION_TABLE)
            .where(MEMBER_ID.eq(item.memberId.value))
            .forUpdate()
            .fetchOne()
        if (existing == null) {
            tx.insertInto(PROJECTION_TABLE)
                .columns(
                    MEMBER_ID, STATUS, REASON_CODE, SOURCE_VERSION, SOURCE_OCCURRED_AT,
                    CREATED_AT, UPDATED_AT,
                )
                .values(
                    item.memberId.value, item.status.name, item.reasonCode?.value,
                    item.sourceVersion, item.sourceOccurredAt.utc(), completedAt.utc(), completedAt.utc(),
                )
                .execute()
            return
        }
        if (
            existing.get(STATUS) != item.status.name ||
            existing.get(REASON_CODE) != item.reasonCode?.value ||
            existing.get(SOURCE_VERSION) != item.sourceVersion ||
            existing.get(SOURCE_OCCURRED_AT)?.toInstant() != item.sourceOccurredAt
        ) throw MembershipEligibilityBootstrapConflictException(
            "Existing eligibility projection conflicts with bootstrap snapshot",
        )
    }

    private fun receipt(context: DSLContext, bootstrapId: UUID): Record? = context.select(
        SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT, MEMBER_COUNT, COMPLETED_AT,
    ).from(BOOTSTRAP_TABLE).where(BOOTSTRAP_ID.eq(bootstrapId)).fetchOne()

    private fun Record.toResult(bootstrapId: UUID, replayed: Boolean) =
        MembershipEligibilityBootstrapResult(
            bootstrapId,
            requireNotNull(get(SOURCE_REVISION)).trim(),
            requireNotNull(get(MANIFEST_SHA256)).trim(),
            requireNotNull(get(MEMBER_COUNT)),
            requireNotNull(get(COMPLETED_AT)).toInstant(),
            replayed,
        )

    private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        fun lockKey(id: UUID): Long = id.mostSignificantBits xor id.leastSignificantBits
        fun table(name: String): Table<Record> = DSL.table(DSL.name(name))
        fun <T> field(name: String, type: Class<T>): Field<T> = DSL.field(DSL.name(name), type)
        val PROJECTION_TABLE = table("circulation_member_eligibility")
        val BOOTSTRAP_TABLE = table("circulation_membership_eligibility_bootstrap")
        val BOOTSTRAP_ID = field("bootstrap_id", UUID::class.java)
        val SOURCE_REVISION = field("source_revision", String::class.java)
        val MANIFEST_SHA256 = field("manifest_sha256", String::class.java)
        val ACTOR_FINGERPRINT = field("actor_fingerprint", String::class.java)
        val MEMBER_COUNT = field("member_count", Int::class.java)
        val COMPLETED_AT = field("completed_at", OffsetDateTime::class.java)
        val MEMBER_ID = field("member_id", UUID::class.java)
        val STATUS = field("status", String::class.java)
        val REASON_CODE = field("reason_code", String::class.java)
        val SOURCE_VERSION = field("source_version", Long::class.java)
        val SOURCE_OCCURRED_AT = field("source_occurred_at", OffsetDateTime::class.java)
        val CREATED_AT = field("created_at", OffsetDateTime::class.java)
        val UPDATED_AT = field("updated_at", OffsetDateTime::class.java)
    }
}
