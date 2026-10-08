package com.mundiapolis.library.membership.adapter.outbound.persistence

import com.mundiapolis.library.membership.dto.LegacyMembershipImportCommand
import com.mundiapolis.library.membership.dto.LegacyMembershipImportItem
import com.mundiapolis.library.membership.dto.LegacyMembershipImportResult
import com.mundiapolis.library.membership.dto.LegacyMembershipImportNotFoundException
import com.mundiapolis.library.membership.dto.MembershipCommandConflictException
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
class JooqLegacyMembershipImportRepository(private val dsl: DSLContext) {
    fun importBatch(
        command: LegacyMembershipImportCommand,
        manifestSha256: String,
        completedAt: Instant,
    ): LegacyMembershipImportResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.execute("select pg_advisory_xact_lock(?)", lockKey(command.importId))
        val receipt = receipt(tx, command.importId)
        if (receipt != null) {
            if (
                receipt.get(MANIFEST_SHA256)?.trim() != manifestSha256 ||
                receipt.get(ACTOR_FINGERPRINT)?.trim() != command.actorFingerprint
            ) throw MembershipCommandConflictException("Import ID is bound to different input or actor")
            return@transactionResult receipt.toResult(command.importId, replayed = true)
        }

        command.items.sortedBy { it.memberId }.forEach { item -> importMember(tx, item, completedAt) }
        val timestamp = completedAt.utc()
        tx.insertInto(IMPORT_TABLE)
            .columns(
                IMPORT_ID, SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT,
                MEMBER_COUNT, QUARANTINED_EVIDENCE_COUNT, COMPLETED_AT,
            )
            .values(
                command.importId, command.sourceRevision, manifestSha256, command.actorFingerprint,
                command.items.size, command.items.size, timestamp,
            )
            .execute()
        LegacyMembershipImportResult(
            command.importId,
            command.sourceRevision,
            manifestSha256,
            command.items.size,
            command.items.size,
            completedAt,
            replayed = false,
        )
    }

    fun importEvidence(importId: UUID): LegacyMembershipImportResult =
        receipt(dsl, importId)?.toResult(importId, replayed = true)
            ?: throw LegacyMembershipImportNotFoundException()

    private fun importMember(tx: DSLContext, item: LegacyMembershipImportItem, completedAt: Instant) {
        val stored = tx.select(
            MEMBER_ID, EMAIL, FULL_NAME, UNIVERSITY_ID, ACCOUNT_STATUS, MEMBERSHIP_ROLE,
            MAX_ACTIVE_LOANS, CURRENT_ACTIVE_LOANS, HAS_UNPAID_FINES, AGGREGATE_VERSION,
            CREATED_AT, UPDATED_AT,
        ).from(MEMBER_TABLE).where(MEMBER_ID.eq(item.memberId)).forUpdate().fetchOne()
        if (stored == null) {
            if (tx.fetchExists(MEMBER_TABLE, EMAIL.eq(item.email))) {
                throw MembershipCommandConflictException("Email is already bound to another member")
            }
            if (tx.fetchExists(MEMBER_TABLE, UNIVERSITY_ID.eq(item.universityId))) {
                throw MembershipCommandConflictException("University ID is already bound to another member")
            }
            tx.insertInto(MEMBER_TABLE)
                .columns(
                    MEMBER_ID, EMAIL, FULL_NAME, UNIVERSITY_ID, ACCOUNT_STATUS, MEMBERSHIP_ROLE,
                    MAX_ACTIVE_LOANS, CURRENT_ACTIVE_LOANS, HAS_UNPAID_FINES, AGGREGATE_VERSION,
                    CREATED_AT, UPDATED_AT,
                )
                .values(
                    item.memberId, item.email, item.fullName, item.universityId, item.status.name,
                    item.role.name, item.maxActiveLoans, item.currentActiveLoans,
                    item.hasUnpaidOverdueFines, 0L, item.createdAt.utc(), item.updatedAt.utc(),
                )
                .execute()
        } else if (!stored.matches(item)) {
            throw MembershipCommandConflictException("Member identity conflicts with imported metadata")
        }

        val quarantine = tx.select(QUARANTINE_MEMBER_ID, REFERENCE_SHA256, QUARANTINE_REASON)
            .from(QUARANTINE_TABLE).where(QUARANTINE_MEMBER_ID.eq(item.memberId)).forUpdate().fetchOne()
        if (quarantine == null) {
            if (tx.fetchExists(QUARANTINE_TABLE, REFERENCE_SHA256.eq(item.evidenceReferenceSha256))) {
                throw MembershipCommandConflictException("Evidence reference is already bound to another member")
            }
            tx.insertInto(QUARANTINE_TABLE)
                .columns(QUARANTINE_MEMBER_ID, REFERENCE_SHA256, QUARANTINE_REASON, QUARANTINED_AT)
                .values(
                    item.memberId, item.evidenceReferenceSha256, QUARANTINE_REASON_VALUE, completedAt.utc(),
                )
                .execute()
        } else if (
            quarantine.get(REFERENCE_SHA256)?.trim() != item.evidenceReferenceSha256 ||
            quarantine.get(QUARANTINE_REASON) != QUARANTINE_REASON_VALUE
        ) throw MembershipCommandConflictException("Quarantined evidence conflicts with imported metadata")
    }

    private fun Record.matches(item: LegacyMembershipImportItem): Boolean =
        get(EMAIL) == item.email && get(FULL_NAME) == item.fullName &&
            get(UNIVERSITY_ID) == item.universityId && get(ACCOUNT_STATUS) == item.status.name &&
            get(MEMBERSHIP_ROLE) == item.role.name && get(MAX_ACTIVE_LOANS) == item.maxActiveLoans &&
            get(CURRENT_ACTIVE_LOANS) == item.currentActiveLoans &&
            get(HAS_UNPAID_FINES) == item.hasUnpaidOverdueFines && get(AGGREGATE_VERSION) == 0L &&
            get(CREATED_AT)?.toInstant() == item.createdAt && get(UPDATED_AT)?.toInstant() == item.updatedAt

    private fun receipt(context: DSLContext, importId: UUID): Record? = context.select(
        SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT, MEMBER_COUNT,
        QUARANTINED_EVIDENCE_COUNT, COMPLETED_AT,
    ).from(IMPORT_TABLE).where(IMPORT_ID.eq(importId)).fetchOne()

    private fun Record.toResult(importId: UUID, replayed: Boolean) = LegacyMembershipImportResult(
        importId,
        requireNotNull(get(SOURCE_REVISION)).trim(),
        requireNotNull(get(MANIFEST_SHA256)).trim(),
        requireNotNull(get(MEMBER_COUNT)),
        requireNotNull(get(QUARANTINED_EVIDENCE_COUNT)),
        requireNotNull(get(COMPLETED_AT)).toInstant(),
        replayed,
    )

    private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
    private fun lockKey(id: UUID): Long = id.mostSignificantBits xor id.leastSignificantBits

    private companion object {
        const val QUARANTINE_REASON_VALUE = "UNVERIFIED_LEGACY_REFERENCE"
        fun table(name: String): Table<Record> = DSL.table(DSL.name(name))
        fun <T> field(name: String, type: Class<T>): Field<T> = DSL.field(DSL.name(name), type)
        val IMPORT_TABLE = table("membership_legacy_import")
        val MEMBER_TABLE = table("membership_member")
        val QUARANTINE_TABLE = table("membership_legacy_evidence_quarantine")
        val IMPORT_ID = field("import_id", UUID::class.java)
        val SOURCE_REVISION = field("source_revision", String::class.java)
        val MANIFEST_SHA256 = field("manifest_sha256", String::class.java)
        val ACTOR_FINGERPRINT = field("actor_fingerprint", String::class.java)
        val MEMBER_COUNT = field("member_count", Int::class.java)
        val QUARANTINED_EVIDENCE_COUNT = field("quarantined_evidence_count", Int::class.java)
        val COMPLETED_AT = field("completed_at", OffsetDateTime::class.java)
        val MEMBER_ID = field("member_id", UUID::class.java)
        val EMAIL = field("email", String::class.java)
        val FULL_NAME = field("full_name", String::class.java)
        val UNIVERSITY_ID = field("university_id", Int::class.java)
        val ACCOUNT_STATUS = field("account_status", String::class.java)
        val MEMBERSHIP_ROLE = field("membership_role", String::class.java)
        val MAX_ACTIVE_LOANS = field("max_active_loans", Int::class.java)
        val CURRENT_ACTIVE_LOANS = field("current_active_loans", Int::class.java)
        val HAS_UNPAID_FINES = field("has_unpaid_overdue_fines", Boolean::class.java)
        val AGGREGATE_VERSION = field("aggregate_version", Long::class.java)
        val CREATED_AT = field("created_at", OffsetDateTime::class.java)
        val UPDATED_AT = field("updated_at", OffsetDateTime::class.java)
        val QUARANTINE_MEMBER_ID = field("member_id", UUID::class.java)
        val REFERENCE_SHA256 = field("reference_sha256", String::class.java)
        val QUARANTINE_REASON = field("reason", String::class.java)
        val QUARANTINED_AT = field("quarantined_at", OffsetDateTime::class.java)
    }
}
