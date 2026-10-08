package com.mundiapolis.library.membership.adapter.outbound.persistence

import com.mundiapolis.library.membership.dto.IdentityEvidenceTransferCommand
import com.mundiapolis.library.membership.dto.IdentityEvidenceTransferNotFoundException
import com.mundiapolis.library.membership.dto.IdentityEvidenceTransferResult
import com.mundiapolis.library.membership.dto.MembershipCommandConflictException
import com.mundiapolis.library.membership.dto.MembershipCommandNotFoundException
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
class JooqIdentityEvidenceTransferRepository(private val dsl: DSLContext) {
    fun transfer(
        command: IdentityEvidenceTransferCommand,
        manifestSha256: String,
        completedAt: Instant,
    ): IdentityEvidenceTransferResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.execute("select pg_advisory_xact_lock(?)", TRANSFER_LOCK_ID)
        val existingReceipt = receipt(tx, command.transferId)
        if (existingReceipt != null) {
            if (
                existingReceipt.get(MANIFEST_SHA256)?.trim() != manifestSha256 ||
                existingReceipt.get(ACTOR_FINGERPRINT)?.trim() != command.actorFingerprint
            ) throw MembershipCommandConflictException("Transfer ID is bound to different input or actor")
            return@transactionResult existingReceipt.toResult(command.transferId, replayed = true)
        }

        if (!tx.fetchExists(MEMBER_TABLE, MEMBER_ID.eq(command.memberId))) {
            throw MembershipCommandNotFoundException()
        }
        val quarantine = tx.select(QUARANTINE_MEMBER_ID, REFERENCE_SHA256, QUARANTINE_REASON)
            .from(QUARANTINE_TABLE)
            .where(QUARANTINE_MEMBER_ID.eq(command.memberId))
            .forUpdate()
            .fetchOne()
            ?: throw MembershipCommandConflictException("Member has no quarantined legacy evidence reference")
        if (
            quarantine.get(REFERENCE_SHA256)?.trim() != command.sourceReferenceSha256 ||
            quarantine.get(QUARANTINE_REASON) != QUARANTINE_REASON_VALUE
        ) throw MembershipCommandConflictException("Transfer does not match the quarantined evidence reference")

        if (
            tx.fetchExists(EVIDENCE_TABLE, EVIDENCE_ID.eq(command.evidenceId)) ||
            tx.fetchExists(EVIDENCE_TABLE, EVIDENCE_MEMBER_ID.eq(command.memberId)) ||
            tx.fetchExists(EVIDENCE_TABLE, OBJECT_KEY.eq(command.objectKey))
        ) throw MembershipCommandConflictException("Identity evidence is already registered")

        tx.insertInto(EVIDENCE_TABLE)
            .columns(
                EVIDENCE_ID, EVIDENCE_MEMBER_ID, OBJECT_KEY, MIME_TYPE, FILE_SIZE,
                CHECKSUM_SHA256, UPLOADED_AT, VERIFICATION_STATUS, SOURCE_REFERENCE_SHA256,
                SCAN_ATTESTATION_SHA256, VERIFIED_AT, RETENTION_EXPIRES_AT,
            )
            .values(
                command.evidenceId, command.memberId, command.objectKey, command.mimeType,
                command.fileSize, command.checksumSha256, command.verifiedAt.utc(), VERIFIED_STATUS,
                command.sourceReferenceSha256, command.scanAttestationSha256,
                command.verifiedAt.utc(), command.retentionExpiresAt.utc(),
            )
            .execute()

        tx.insertInto(TRANSFER_TABLE)
            .columns(
                TRANSFER_ID, TRANSFER_EVIDENCE_ID, MANIFEST_SHA256, ACTOR_FINGERPRINT, COMPLETED_AT,
            )
            .values(
                command.transferId, command.evidenceId, manifestSha256, command.actorFingerprint,
                completedAt.utc(),
            )
            .execute()

        IdentityEvidenceTransferResult(
            command.transferId,
            command.evidenceId,
            manifestSha256,
            completedAt,
            replayed = false,
        )
    }

    fun receipt(transferId: UUID): IdentityEvidenceTransferResult =
        receipt(dsl, transferId)?.toResult(transferId, replayed = true)
            ?: throw IdentityEvidenceTransferNotFoundException()

    private fun receipt(context: DSLContext, transferId: UUID): Record? = context.select(
        TRANSFER_EVIDENCE_ID, MANIFEST_SHA256, ACTOR_FINGERPRINT, COMPLETED_AT,
    ).from(TRANSFER_TABLE).where(TRANSFER_ID.eq(transferId)).fetchOne()

    private fun Record.toResult(transferId: UUID, replayed: Boolean) = IdentityEvidenceTransferResult(
        transferId,
        requireNotNull(get(TRANSFER_EVIDENCE_ID)),
        requireNotNull(get(MANIFEST_SHA256)).trim(),
        requireNotNull(get(COMPLETED_AT)).toInstant(),
        replayed,
    )

    private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val TRANSFER_LOCK_ID = 71_202_507
        const val QUARANTINE_REASON_VALUE = "UNVERIFIED_LEGACY_REFERENCE"
        const val VERIFIED_STATUS = "VERIFIED"
        fun table(name: String): Table<Record> = DSL.table(DSL.name(name))
        fun <T> field(name: String, type: Class<T>): Field<T> = DSL.field(DSL.name(name), type)
        val MEMBER_TABLE = table("membership_member")
        val QUARANTINE_TABLE = table("membership_legacy_evidence_quarantine")
        val EVIDENCE_TABLE = table("membership_identity_evidence")
        val TRANSFER_TABLE = table("membership_identity_evidence_transfer")
        val MEMBER_ID = field("member_id", UUID::class.java)
        val QUARANTINE_MEMBER_ID = field("member_id", UUID::class.java)
        val REFERENCE_SHA256 = field("reference_sha256", String::class.java)
        val QUARANTINE_REASON = field("reason", String::class.java)
        val EVIDENCE_ID = field("evidence_id", UUID::class.java)
        val EVIDENCE_MEMBER_ID = field("member_id", UUID::class.java)
        val OBJECT_KEY = field("object_key", String::class.java)
        val MIME_TYPE = field("mime_type", String::class.java)
        val FILE_SIZE = field("file_size", Int::class.java)
        val CHECKSUM_SHA256 = field("checksum_sha256", String::class.java)
        val UPLOADED_AT = field("uploaded_at", OffsetDateTime::class.java)
        val VERIFICATION_STATUS = field("verification_status", String::class.java)
        val SOURCE_REFERENCE_SHA256 = field("source_reference_sha256", String::class.java)
        val SCAN_ATTESTATION_SHA256 = field("scan_attestation_sha256", String::class.java)
        val VERIFIED_AT = field("verified_at", OffsetDateTime::class.java)
        val RETENTION_EXPIRES_AT = field("retention_expires_at", OffsetDateTime::class.java)
        val TRANSFER_ID = field("transfer_id", UUID::class.java)
        val TRANSFER_EVIDENCE_ID = field("evidence_id", UUID::class.java)
        val MANIFEST_SHA256 = field("manifest_sha256", String::class.java)
        val ACTOR_FINGERPRINT = field("actor_fingerprint", String::class.java)
        val COMPLETED_AT = field("completed_at", OffsetDateTime::class.java)
    }
}
