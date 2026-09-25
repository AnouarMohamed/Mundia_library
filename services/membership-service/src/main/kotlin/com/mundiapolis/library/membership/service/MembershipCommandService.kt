package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.adapter.outbound.persistence.JooqMembershipCommandRepository
import com.mundiapolis.library.membership.dto.ChangeAccountStatusCommand
import com.mundiapolis.library.membership.dto.InvalidMembershipActorException
import com.mundiapolis.library.membership.dto.InvalidMembershipCommandException
import com.mundiapolis.library.membership.dto.MembershipCommandExecution
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat

@Service
class MembershipCommandService(
    private val repository: JooqMembershipCommandRepository,
    private val clock: Clock,
) {
    fun changeAccountStatus(command: ChangeAccountStatusCommand): MembershipCommandExecution {
        if (!FINGERPRINT.matches(command.ownerFingerprint)) {
            throw InvalidMembershipActorException("Command owner fingerprint is invalid")
        }
        if (command.expectedVersion < 0) {
            throw InvalidMembershipCommandException("Expected version cannot be negative")
        }
        val reason = command.reason.trim()
        if (reason.length !in 8..500 || reason.any(Char::isISOControl)) {
            throw InvalidMembershipCommandException("reason must contain between 8 and 500 safe characters")
        }
        val idempotencyKey = command.idempotencyKey.trim()
        if (idempotencyKey.length !in 16..128 || idempotencyKey.any(Char::isISOControl)) {
            throw InvalidMembershipCommandException(
                "Idempotency-Key must contain between 16 and 128 safe characters",
            )
        }
        val normalized = command.copy(reason = reason, idempotencyKey = idempotencyKey)
        return repository.changeAccountStatus(
            command = normalized,
            requestFingerprint = fingerprint(
                normalized.memberId.toString(),
                normalized.actorMemberId.toString(),
                normalized.expectedVersion.toString(),
                normalized.status.name,
                normalized.reason,
            ),
            now = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }

    private fun fingerprint(vararg values: String): String {
        val canonical = listOf("CHANGE_ACCOUNT_STATUS", *values).joinToString("\u001f")
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private companion object {
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
