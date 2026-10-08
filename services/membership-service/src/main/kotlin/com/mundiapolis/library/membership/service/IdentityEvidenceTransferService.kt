package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.adapter.outbound.persistence.JooqIdentityEvidenceTransferRepository
import com.mundiapolis.library.membership.dto.IdentityEvidenceTransferCommand
import com.mundiapolis.library.membership.dto.IdentityEvidenceTransferResult
import com.mundiapolis.library.membership.dto.InvalidMembershipActorException
import com.mundiapolis.library.membership.dto.InvalidMembershipCommandException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class IdentityEvidenceTransferService(
    private val repository: JooqIdentityEvidenceTransferRepository,
    private val clock: Clock,
) {
    fun transfer(command: IdentityEvidenceTransferCommand): IdentityEvidenceTransferResult {
        if (!SHA256.matches(command.actorFingerprint)) {
            throw InvalidMembershipActorException("Identity evidence transfer actor fingerprint is invalid")
        }
        val normalized = command.copy(
            objectKey = command.objectKey.trim(),
            mimeType = command.mimeType.trim().lowercase(),
            checksumSha256 = command.checksumSha256.lowercase(),
            sourceReferenceSha256 = command.sourceReferenceSha256.lowercase(),
            scanAttestationSha256 = command.scanAttestationSha256.lowercase(),
            verifiedAt = command.verifiedAt.truncatedTo(ChronoUnit.MICROS),
            retentionExpiresAt = command.retentionExpiresAt.truncatedTo(ChronoUnit.MICROS),
        )
        validate(normalized)
        val manifest = sha256(canonicalManifest(normalized))
        return repository.transfer(
            normalized,
            manifest,
            clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }

    fun receipt(transferId: UUID): IdentityEvidenceTransferResult = repository.receipt(transferId)

    private fun validate(command: IdentityEvidenceTransferCommand) {
        val now = clock.instant()
        val expectedKey = "identity-evidence/${command.memberId}/${command.evidenceId}"
        if (command.objectKey != expectedKey) {
            throw InvalidMembershipCommandException("objectKey must be the server-approved opaque evidence key")
        }
        if (command.mimeType !in ALLOWED_MIME_TYPES || command.fileSize !in 1..MAX_FILE_BYTES) {
            throw InvalidMembershipCommandException("Identity evidence media metadata is invalid")
        }
        if (
            !SHA256.matches(command.checksumSha256) ||
            !SHA256.matches(command.sourceReferenceSha256) ||
            !SHA256.matches(command.scanAttestationSha256)
        ) {
            throw InvalidMembershipCommandException("Identity evidence digests must be lowercase SHA-256")
        }
        if (command.verifiedAt > now.plus(MAXIMUM_FUTURE_SKEW)) {
            throw InvalidMembershipCommandException("verifiedAt exceeds the allowed future clock skew")
        }
        val retention = Duration.between(command.verifiedAt, command.retentionExpiresAt)
        if (retention.isZero || retention.isNegative || retention > MAXIMUM_RETENTION) {
            throw InvalidMembershipCommandException("Identity evidence retention window is invalid")
        }
        if (command.retentionExpiresAt <= now) {
            throw InvalidMembershipCommandException("Identity evidence retention deadline has already elapsed")
        }
    }

    private fun canonicalManifest(command: IdentityEvidenceTransferCommand): String = buildString {
        append(MANIFEST_VERSION)
        listOf(
            command.transferId.toString(), command.memberId.toString(), command.evidenceId.toString(),
            command.objectKey, command.mimeType, command.fileSize.toString(), command.checksumSha256,
            command.sourceReferenceSha256, command.scanAttestationSha256,
            command.verifiedAt.toString(), command.retentionExpiresAt.toString(),
        ).forEach { value -> append('\u001f').append(value.length).append(':').append(value) }
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val MANIFEST_VERSION = "membership-identity-evidence-transfer-v1"
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "application/pdf")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val MAXIMUM_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
        val MAXIMUM_RETENTION: Duration = Duration.ofDays(366L * 7L)
    }
}
