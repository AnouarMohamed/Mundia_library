package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.adapter.outbound.persistence.JooqLegacyMembershipImportRepository
import com.mundiapolis.library.membership.dto.InvalidMembershipActorException
import com.mundiapolis.library.membership.dto.InvalidMembershipCommandException
import com.mundiapolis.library.membership.dto.LegacyMembershipImportCommand
import com.mundiapolis.library.membership.dto.LegacyMembershipImportItem
import com.mundiapolis.library.membership.dto.LegacyMembershipImportResult
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class LegacyMembershipImportService(
    private val repository: JooqLegacyMembershipImportRepository,
    private val clock: Clock,
) {
    fun importBatch(command: LegacyMembershipImportCommand): LegacyMembershipImportResult {
        if (!SHA256.matches(command.actorFingerprint)) {
            throw InvalidMembershipActorException("Membership import actor fingerprint is invalid")
        }
        if (!SHA256.matches(command.sourceRevision)) {
            throw InvalidMembershipCommandException("sourceRevision must be a lowercase SHA-256 digest")
        }
        if (command.items.size !in 1..MAX_BATCH_SIZE) {
            throw InvalidMembershipCommandException("items must contain between 1 and $MAX_BATCH_SIZE members")
        }
        requireUnique(command.items.map { it.memberId }, "member IDs")
        requireUnique(command.items.map { it.email.lowercase() }, "emails")
        requireUnique(command.items.map { it.universityId }, "university IDs")
        requireUnique(command.items.map { it.evidenceReferenceSha256.lowercase() }, "evidence references")
        val normalized = command.copy(items = command.items.map(::normalize))
        val manifest = sha256(canonicalManifest(normalized))
        return repository.importBatch(normalized, manifest, clock.instant().truncatedTo(ChronoUnit.MICROS))
    }

    fun importEvidence(importId: UUID): LegacyMembershipImportResult = repository.importEvidence(importId)

    private fun normalize(item: LegacyMembershipImportItem): LegacyMembershipImportItem {
        val normalized = item.copy(
            email = item.email.trim().lowercase(),
            fullName = item.fullName.safeText("fullName", 200),
            createdAt = item.createdAt.truncatedTo(ChronoUnit.MICROS),
            updatedAt = item.updatedAt.truncatedTo(ChronoUnit.MICROS),
            evidenceReferenceSha256 = item.evidenceReferenceSha256.lowercase(),
            contentSha256 = item.contentSha256.lowercase(),
        )
        if (
            normalized.email.length !in 3..320 || normalized.email.any(Char::isISOControl) ||
            normalized.email.any(Char::isWhitespace) || normalized.email.count { it == '@' } != 1 ||
            normalized.universityId <= 0 || normalized.maxActiveLoans !in 0..100 ||
            normalized.currentActiveLoans !in 0..normalized.maxActiveLoans ||
            normalized.updatedAt < normalized.createdAt ||
            !SHA256.matches(normalized.evidenceReferenceSha256) || !SHA256.matches(normalized.contentSha256)
        ) throw InvalidMembershipCommandException("Legacy membership metadata is invalid")
        if (sha256(canonicalItem(normalized, includeContentHash = false)) != normalized.contentSha256) {
            throw InvalidMembershipCommandException("contentSha256 does not match the canonical member")
        }
        return normalized
    }

    private fun canonicalManifest(command: LegacyMembershipImportCommand): String = buildString {
        append(MANIFEST_VERSION)
        field(command.importId.toString())
        field(command.sourceRevision)
        command.items.sortedBy { it.memberId }.forEach { field(canonicalItem(it, includeContentHash = true)) }
    }

    private fun canonicalItem(item: LegacyMembershipImportItem, includeContentHash: Boolean): String = buildString {
        append(ITEM_VERSION)
        field(item.memberId.toString())
        field(item.email)
        field(item.fullName)
        field(item.universityId.toString())
        field(item.status.name)
        field(item.role.name)
        field(item.maxActiveLoans.toString())
        field(item.currentActiveLoans.toString())
        field(item.hasUnpaidOverdueFines.toString())
        field(item.createdAt.toEpochMilli().toString())
        field(item.updatedAt.toEpochMilli().toString())
        field(item.evidenceReferenceSha256)
        if (includeContentHash) field(item.contentSha256)
    }

    private fun StringBuilder.field(value: String) {
        append('\u001f').append(value.length).append(':').append(value)
    }

    private fun String.safeText(name: String, maximum: Int): String {
        val normalized = trim()
        if (normalized.isEmpty() || normalized.length > maximum || normalized.any(Char::isISOControl)) {
            throw InvalidMembershipCommandException("$name must contain 1 to $maximum safe characters")
        }
        return normalized
    }

    private fun requireUnique(values: List<Any>, label: String) {
        if (values.toSet().size != values.size) {
            throw InvalidMembershipCommandException("items must contain unique $label")
        }
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val MAX_BATCH_SIZE = 100
        const val MANIFEST_VERSION = "membership-legacy-import-v1"
        const val ITEM_VERSION = "membership-legacy-item-v1"
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
