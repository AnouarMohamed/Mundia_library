package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.CommandOperation
import com.mundiapolis.library.circulation.application.model.DuplicatePaymentReferenceException
import com.mundiapolis.library.circulation.application.model.FineBalanceConflictException
import com.mundiapolis.library.circulation.application.model.FineCommandExecution
import com.mundiapolis.library.circulation.application.model.FineCommandResult
import com.mundiapolis.library.circulation.application.model.FineNotFoundException
import com.mundiapolis.library.circulation.application.model.FineOutboxEvent
import com.mundiapolis.library.circulation.application.model.FinePersistenceConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.IncompleteIdempotencyRecordException
import com.mundiapolis.library.circulation.application.model.InvalidFineAdjustmentException
import com.mundiapolis.library.circulation.application.model.InvalidFineAmountException
import com.mundiapolis.library.circulation.application.model.LoanNotEligibleForFineException
import com.mundiapolis.library.circulation.application.model.LoanNotFoundException
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentCommand
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentUseCase
import com.mundiapolis.library.circulation.application.port.outbound.FineIdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.FineLedgerStore
import com.mundiapolis.library.circulation.application.port.outbound.FineOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.FineStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.LoanStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.Fine
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntry
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryType
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

class FineCommandService(
    private val transactionRunner: TransactionRunner,
    private val loanStore: LoanStore,
    private val fineStore: FineStore,
    private val fineLedgerStore: FineLedgerStore,
    private val idempotencyStore: FineIdempotencyStore,
    private val outboxEventStore: FineOutboxEventStore,
    private val timeProvider: TimeProvider,
    private val identifierGenerator: IdentifierGenerator,
    private val currency: String,
    private val idempotencyRetention: Duration,
) : AssessFineUseCase,
    RecordFinePaymentUseCase,
    AdjustFineUseCase {
    override fun assess(command: AssessFineCommand): FineCommandExecution {
        requireFineAmount(command.amountMinor)
        val operation = CommandOperation.ASSESS_FINE
        val fingerprint = fingerprint(
            operation,
            command.loanId.value.toString(),
            command.amountMinor.toString(),
            command.reason.value,
        )

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val loan = loanStore.lockById(command.loanId)
                ?: throw LoanNotFoundException(command.loanId)
            if (loan.status !in setOf(LoanStatus.ACTIVE, LoanStatus.RETURNED)) {
                throw LoanNotEligibleForFineException(loan.id)
            }
            val fine = Fine.assess(
                id = FineId(identifierGenerator.next()),
                loanId = loan.id,
                memberId = loan.memberId,
                currency = currency,
                amountMinor = command.amountMinor,
                assessedAt = now,
            )
            if (!fineStore.create(fine, now)) {
                throw FinePersistenceConflictException()
            }
            val entry = FineLedgerEntry(
                id = FineLedgerEntryId(identifierGenerator.next()),
                fineId = fine.id,
                fineVersion = fine.version,
                type = FineLedgerEntryType.ASSESSMENT,
                deltaMinor = command.amountMinor,
                actorFingerprint = command.principal.idempotencyOwner.fingerprint,
                reason = command.reason.value,
                externalReference = null,
                occurredAt = now,
            )
            if (!fineLedgerStore.append(entry)) {
                throw FinePersistenceConflictException()
            }
            fine to entry
        }
    }

    override fun recordPayment(command: RecordFinePaymentCommand): FineCommandExecution {
        requireFineAmount(command.amountMinor)
        val operation = CommandOperation.RECORD_FINE_PAYMENT
        val fingerprint = fingerprint(
            operation,
            command.fineId.value.toString(),
            command.amountMinor.toString(),
            command.externalReference.value,
        )

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val current = requireFine(command.fineId)
            if (command.amountMinor > current.balanceMinor) {
                throw FineBalanceConflictException()
            }
            val paid = current.recordPayment(command.amountMinor)
            if (!fineStore.update(paid, current.version, now)) {
                throw FinePersistenceConflictException()
            }
            val entry = FineLedgerEntry(
                id = FineLedgerEntryId(identifierGenerator.next()),
                fineId = paid.id,
                fineVersion = paid.version,
                type = FineLedgerEntryType.PAYMENT,
                deltaMinor = -command.amountMinor,
                actorFingerprint = command.principal.idempotencyOwner.fingerprint,
                reason = null,
                externalReference = command.externalReference.value,
                occurredAt = now,
            )
            if (!fineLedgerStore.append(entry)) {
                throw DuplicatePaymentReferenceException()
            }
            paid to entry
        }
    }

    override fun adjust(command: AdjustFineCommand): FineCommandExecution {
        if (
            command.deltaMinor == 0L ||
            command.deltaMinor !in -Fine.MAX_AMOUNT_MINOR..Fine.MAX_AMOUNT_MINOR
        ) {
            throw InvalidFineAdjustmentException()
        }
        val operation = CommandOperation.ADJUST_FINE
        val fingerprint = fingerprint(
            operation,
            command.fineId.value.toString(),
            command.deltaMinor.toString(),
            command.reason.value,
        )

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val current = requireFine(command.fineId)
            val adjustedBalance = try {
                Math.addExact(current.balanceMinor, command.deltaMinor)
            } catch (_: ArithmeticException) {
                throw FineBalanceConflictException()
            }
            if (adjustedBalance !in 0..Fine.MAX_AMOUNT_MINOR) {
                throw FineBalanceConflictException()
            }
            val adjusted = current.adjust(command.deltaMinor)
            if (!fineStore.update(adjusted, current.version, now)) {
                throw FinePersistenceConflictException()
            }
            val entry = FineLedgerEntry(
                id = FineLedgerEntryId(identifierGenerator.next()),
                fineId = adjusted.id,
                fineVersion = adjusted.version,
                type = FineLedgerEntryType.ADJUSTMENT,
                deltaMinor = command.deltaMinor,
                actorFingerprint = command.principal.idempotencyOwner.fingerprint,
                reason = command.reason.value,
                externalReference = null,
                occurredAt = now,
            )
            if (!fineLedgerStore.append(entry)) {
                throw FinePersistenceConflictException()
            }
            adjusted to entry
        }
    }

    private fun executeIdempotently(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        requestFingerprint: String,
        action: (Instant) -> Pair<Fine, FineLedgerEntry>,
    ): FineCommandExecution = transactionRunner.required {
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val claimed = idempotencyStore.claim(
            owner = owner,
            key = key,
            operation = operation,
            requestFingerprint = requestFingerprint,
            createdAt = now,
            expiresAt = now.plus(idempotencyRetention),
        )
        if (!claimed) {
            val stored = idempotencyStore.find(owner, key)
                ?: throw IncompleteIdempotencyRecordException()
            if (stored.operation != operation || stored.requestFingerprint != requestFingerprint) {
                throw IdempotencyKeyConflictException()
            }
            return@required FineCommandExecution(
                result = stored.result ?: throw IncompleteIdempotencyRecordException(),
                replayed = true,
            )
        }

        val (fine, entry) = action(now)
        val result = FineCommandResult.from(fine, entry)
        outboxEventStore.append(
            FineOutboxEvent(
                id = identifierGenerator.next(),
                aggregateId = result.fineId,
                aggregateVersion = result.version,
                eventType = operation.eventType,
                eventVersion = 1,
                occurredAt = now,
                result = result,
            ),
        )
        idempotencyStore.complete(owner, key, operation, result, now)
        FineCommandExecution(result = result, replayed = false)
    }

    private fun requireFine(id: FineId): Fine =
        fineStore.lockById(id) ?: throw FineNotFoundException(id)

    private fun requireFineAmount(amountMinor: Long) {
        if (amountMinor !in 1..Fine.MAX_AMOUNT_MINOR) {
            throw InvalidFineAmountException()
        }
    }

    private fun fingerprint(operation: CommandOperation, vararg values: String): String {
        val canonical = buildString {
            append("circulation-command-v1")
            append('\u001f')
            append(operation.name)
            values.forEach {
                append('\u001f')
                append(it)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
