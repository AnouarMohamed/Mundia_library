package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException
import com.mundiapolis.library.circulation.application.model.IncompleteIdempotencyRecordException
import com.mundiapolis.library.circulation.application.model.InvalidCirculationPolicyException
import com.mundiapolis.library.circulation.application.model.PolicyCommandExecution
import com.mundiapolis.library.circulation.application.model.PolicyOutboxEvent
import com.mundiapolis.library.circulation.application.model.PolicyRevisionConflictException
import com.mundiapolis.library.circulation.application.model.PolicyRevisionNotFoundException
import com.mundiapolis.library.circulation.application.port.inbound.UpdateCirculationPolicyCommand
import com.mundiapolis.library.circulation.application.port.inbound.UpdateCirculationPolicyUseCase
import com.mundiapolis.library.circulation.application.port.outbound.CirculationPolicyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.PolicyIdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.PolicyOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.CirculationPolicy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

class PolicyCommandService(
    private val transactionRunner: TransactionRunner,
    private val policyStore: CirculationPolicyStore,
    private val idempotencyStore: PolicyIdempotencyStore,
    private val outboxEventStore: PolicyOutboxEventStore,
    private val timeProvider: TimeProvider,
    private val identifierGenerator: IdentifierGenerator,
    private val idempotencyRetention: Duration,
) : UpdateCirculationPolicyUseCase {
    override fun update(command: UpdateCirculationPolicyCommand): PolicyCommandExecution =
        transactionRunner.required {
            val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
            val owner = command.principal.idempotencyOwner
            val fingerprint = fingerprint(command)
            val claimed = idempotencyStore.claim(
                owner,
                command.idempotencyKey,
                fingerprint,
                now,
                now.plus(idempotencyRetention),
            )
            if (!claimed) {
                val stored = idempotencyStore.find(owner, command.idempotencyKey)
                    ?: throw IncompleteIdempotencyRecordException()
                if (stored.requestFingerprint != fingerprint) {
                    throw IdempotencyKeyConflictException()
                }
                val revision = stored.revisionId
                    ?: throw IncompleteIdempotencyRecordException()
                val policy = policyStore.findRevision(revision)
                    ?: throw PolicyRevisionNotFoundException()
                return@required PolicyCommandExecution(policy.toView(), replayed = true)
            }

            val expected = parseRevision(command.expectedRevision)
            val current = policyStore.lockCurrent()
            if (current.revisionId != expected) {
                throw PolicyRevisionConflictException()
            }
            val values = command.values
            val policy = try {
                CirculationPolicy(
                    revisionId = identifierGenerator.next(),
                    sequence = current.sequence + 1,
                    defaultLoanPeriod = values.defaultLoanPeriod,
                    renewalPeriod = values.renewalPeriod,
                    maximumRenewals = values.maximumRenewals,
                    fineCurrency = values.fineCurrency,
                    reservationHoldPeriod = values.reservationHoldPeriod,
                    maximumActiveReservations = values.maximumActiveReservations,
                    effectiveAt = now,
                    actorFingerprint = owner.fingerprint,
                )
            } catch (exception: IllegalArgumentException) {
                throw InvalidCirculationPolicyException(
                    exception.message ?: "Circulation policy is invalid",
                )
            }
            if (!policyStore.install(policy, current.revisionId)) {
                throw PolicyRevisionConflictException()
            }
            outboxEventStore.append(PolicyOutboxEvent(identifierGenerator.next(), policy, now))
            idempotencyStore.complete(owner, command.idempotencyKey, policy.revisionId, now)
            PolicyCommandExecution(policy.toView(), replayed = false)
        }

    private fun parseRevision(raw: String): UUID = try {
        UUID.fromString(raw).also {
            if (it.toString() != raw.lowercase()) throw IllegalArgumentException()
        }
    } catch (_: IllegalArgumentException) {
        throw InvalidCirculationPolicyException("Expected policy revision must be a canonical UUID")
    }

    private fun fingerprint(command: UpdateCirculationPolicyCommand): String {
        val values = command.values
        val canonical = listOf(
            "circulation-policy-command-v1",
            command.expectedRevision.lowercase(),
            values.defaultLoanPeriod.toString(),
            values.renewalPeriod.toString(),
            values.maximumRenewals.toString(),
            values.fineCurrency,
            values.reservationHoldPeriod.toString(),
            values.maximumActiveReservations.toString(),
        ).joinToString("\u001f")
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
