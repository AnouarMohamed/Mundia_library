package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.InvalidMembershipEligibilityBootstrapException
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapCommand
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapItem
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapResult
import com.mundiapolis.library.circulation.application.port.outbound.MembershipEligibilityBootstrapStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.domain.model.MemberEligibility
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class MembershipEligibilityBootstrapService(
    private val repository: MembershipEligibilityBootstrapStore,
    private val timeProvider: TimeProvider,
) {
    fun bootstrap(command: MembershipEligibilityBootstrapCommand): MembershipEligibilityBootstrapResult {
        if (!SHA256.matches(command.sourceRevision) || !SHA256.matches(command.actorFingerprint)) {
            throw InvalidMembershipEligibilityBootstrapException("Bootstrap identity or source digest is invalid")
        }
        if (command.items.size !in 1..MAX_BATCH_SIZE) {
            throw InvalidMembershipEligibilityBootstrapException(
                "items must contain between 1 and $MAX_BATCH_SIZE eligibility snapshots",
            )
        }
        if (command.items.map { it.memberId }.toSet().size != command.items.size) {
            throw InvalidMembershipEligibilityBootstrapException("items must contain unique member IDs")
        }
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val normalized = command.copy(items = command.items.map { normalize(it, now) })
        val manifest = sha256(canonicalManifest(normalized))
        return repository.bootstrap(normalized, manifest, now)
    }

    fun receipt(bootstrapId: UUID): MembershipEligibilityBootstrapResult = repository.receipt(bootstrapId)

    private fun normalize(
        item: MembershipEligibilityBootstrapItem,
        now: java.time.Instant,
    ): MembershipEligibilityBootstrapItem {
        val normalized = item.copy(
            sourceOccurredAt = item.sourceOccurredAt.truncatedTo(ChronoUnit.MICROS),
            contentSha256 = item.contentSha256.lowercase(),
        )
        try {
            MemberEligibility(
                normalized.memberId,
                normalized.status,
                normalized.reasonCode,
                normalized.sourceVersion,
                normalized.sourceOccurredAt,
            )
        } catch (exception: IllegalArgumentException) {
            throw InvalidMembershipEligibilityBootstrapException(
                exception.message ?: "Eligibility snapshot is invalid",
            )
        }
        if (
            normalized.sourceOccurredAt > now.plus(MAXIMUM_FUTURE_CLOCK_SKEW) ||
            !SHA256.matches(normalized.contentSha256) ||
            sha256(canonicalItem(normalized, includeContentHash = false)) != normalized.contentSha256
        ) throw InvalidMembershipEligibilityBootstrapException("Eligibility snapshot integrity is invalid")
        return normalized
    }

    private fun canonicalManifest(command: MembershipEligibilityBootstrapCommand): String = buildString {
        append(MANIFEST_VERSION)
        field(command.bootstrapId.toString())
        field(command.sourceRevision)
        command.items.sortedBy { it.memberId.value }.forEach {
            field(canonicalItem(it, includeContentHash = true))
        }
    }

    private fun canonicalItem(
        item: MembershipEligibilityBootstrapItem,
        includeContentHash: Boolean,
    ): String = buildString {
        append(ITEM_VERSION)
        field(item.memberId.value.toString())
        field(item.status.name)
        field(item.reasonCode?.value ?: NULL_MARKER)
        field(item.sourceVersion.toString())
        field(item.sourceOccurredAt.toString())
        if (includeContentHash) field(item.contentSha256)
    }

    private fun StringBuilder.field(value: String) {
        append('\u001f').append(value.length).append(':').append(value)
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val MAX_BATCH_SIZE = 100
        const val MANIFEST_VERSION = "circulation-membership-eligibility-bootstrap-v1"
        const val ITEM_VERSION = "circulation-membership-eligibility-item-v1"
        const val NULL_MARKER = "<null>"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val MAXIMUM_FUTURE_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}
