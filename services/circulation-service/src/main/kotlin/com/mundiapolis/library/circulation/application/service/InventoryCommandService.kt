package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.ConcurrentInventoryUpdateException
import com.mundiapolis.library.circulation.application.model.CopyAlreadyExistsException
import com.mundiapolis.library.circulation.application.model.CopyNotFoundException
import com.mundiapolis.library.circulation.application.model.CopyStateConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.IncompleteIdempotencyRecordException
import com.mundiapolis.library.circulation.application.model.InventoryCommandExecution
import com.mundiapolis.library.circulation.application.model.InventoryCommandResult
import com.mundiapolis.library.circulation.application.model.InventoryAuditEntry
import com.mundiapolis.library.circulation.application.model.InventoryOperation
import com.mundiapolis.library.circulation.application.model.InventoryOutboxEvent
import com.mundiapolis.library.circulation.application.port.inbound.ChangeCopyConditionCommand
import com.mundiapolis.library.circulation.application.port.inbound.ChangeCopyConditionUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RegisterCopyCommand
import com.mundiapolis.library.circulation.application.port.inbound.RegisterCopyUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RelocateCopyCommand
import com.mundiapolis.library.circulation.application.port.inbound.RelocateCopyUseCase
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.InventoryAuditStore
import com.mundiapolis.library.circulation.application.port.outbound.InventoryIdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.InventoryOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.Copy
import com.mundiapolis.library.circulation.domain.model.CopyId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

class InventoryCommandService(
    private val transactionRunner: TransactionRunner,
    private val copyStore: CopyStore,
    private val idempotencyStore: InventoryIdempotencyStore,
    private val auditStore: InventoryAuditStore,
    private val outboxEventStore: InventoryOutboxEventStore,
    private val timeProvider: TimeProvider,
    private val identifierGenerator: IdentifierGenerator,
    private val reservationQueueService: ReservationQueueService,
    private val idempotencyRetention: Duration,
) : RegisterCopyUseCase,
    ChangeCopyConditionUseCase,
    RelocateCopyUseCase {
    override fun register(command: RegisterCopyCommand): InventoryCommandExecution {
        val operation = InventoryOperation.REGISTER_COPY
        val fingerprint = fingerprint(
            operation,
            command.copyId.value.toString(),
            command.editionId.value.toString(),
            command.branchId.value.toString(),
            command.barcode.value,
            command.shelfLocation?.value ?: NULL_MARKER,
            command.reason.value,
        )
        return executeIdempotently(
            owner = command.principal.idempotencyOwner,
            key = command.idempotencyKey,
            operation = operation,
            requestFingerprint = fingerprint,
            reason = command.reason.value,
        ) { now ->
            val copy = Copy.register(
                id = command.copyId,
                editionId = command.editionId,
                branchId = command.branchId,
                barcode = command.barcode,
                shelfLocation = command.shelfLocation,
            )
            if (!copyStore.create(copy, now)) {
                throw CopyAlreadyExistsException()
            }
            reservationQueueService.claimNewlyAvailableCopy(
                copy.editionId,
                copy.id,
                now,
                command.principal.idempotencyOwner.fingerprint,
            )
            InventoryMutation(previous = null, current = copy)
        }
    }

    override fun changeCondition(command: ChangeCopyConditionCommand): InventoryCommandExecution {
        val operation = InventoryOperation.CHANGE_COPY_CONDITION
        val fingerprint = fingerprint(
            operation,
            command.copyId.value.toString(),
            command.target.name,
            command.reason.value,
        )
        return executeIdempotently(
            owner = command.principal.idempotencyOwner,
            key = command.idempotencyKey,
            operation = operation,
            requestFingerprint = fingerprint,
            reason = command.reason.value,
        ) { now ->
            val current = requireCopy(command.copyId)
            val changed = try {
                current.changeCondition(command.target)
            } catch (exception: IllegalArgumentException) {
                throw CopyStateConflictException(
                    exception.message ?: "Copy condition transition is not allowed",
                )
            }
            if (!copyStore.update(changed, current.version, now)) {
                throw ConcurrentInventoryUpdateException()
            }
            if (changed.status == com.mundiapolis.library.circulation.domain.model.CopyStatus.AVAILABLE) {
                reservationQueueService.claimNewlyAvailableCopy(
                    changed.editionId,
                    changed.id,
                    now,
                    command.principal.idempotencyOwner.fingerprint,
                )
            }
            InventoryMutation(previous = current, current = changed)
        }
    }

    override fun relocate(command: RelocateCopyCommand): InventoryCommandExecution {
        val operation = InventoryOperation.RELOCATE_COPY
        val fingerprint = fingerprint(
            operation,
            command.copyId.value.toString(),
            command.branchId.value.toString(),
            command.shelfLocation.value,
            command.reason.value,
        )
        return executeIdempotently(
            owner = command.principal.idempotencyOwner,
            key = command.idempotencyKey,
            operation = operation,
            requestFingerprint = fingerprint,
            reason = command.reason.value,
        ) { now ->
            val current = requireCopy(command.copyId)
            val relocated = try {
                current.relocate(command.branchId, command.shelfLocation)
            } catch (exception: IllegalArgumentException) {
                throw CopyStateConflictException(
                    exception.message ?: "Copy relocation is not allowed",
                )
            }
            if (!copyStore.update(relocated, current.version, now)) {
                throw ConcurrentInventoryUpdateException()
            }
            InventoryMutation(previous = current, current = relocated)
        }
    }

    private fun requireCopy(id: CopyId): Copy =
        copyStore.lockById(id) ?: throw CopyNotFoundException(id)

    private fun executeIdempotently(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: InventoryOperation,
        requestFingerprint: String,
        reason: String,
        action: (Instant) -> InventoryMutation,
    ): InventoryCommandExecution = transactionRunner.required {
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
            return@required InventoryCommandExecution(
                result = stored.result ?: throw IncompleteIdempotencyRecordException(),
                replayed = true,
            )
        }

        val mutation = action(now)
        val result = InventoryCommandResult.from(mutation.current, now)
        auditStore.append(
            InventoryAuditEntry(
                id = identifierGenerator.next(),
                operation = operation,
                previous = mutation.previous?.let { previous ->
                    InventoryCommandResult.from(previous, now)
                },
                result = result,
                actorFingerprint = owner.fingerprint,
                reason = reason,
            ),
        )
        outboxEventStore.append(
            InventoryOutboxEvent(
                id = identifierGenerator.next(),
                aggregateId = result.copyId,
                aggregateVersion = result.version,
                eventType = operation.eventType,
                eventVersion = 1,
                occurredAt = now,
                result = result,
                actorFingerprint = owner.fingerprint,
                reason = reason,
            ),
        )
        idempotencyStore.complete(owner, key, operation, result, now)
        InventoryCommandExecution(result = result, replayed = false)
    }

    private fun fingerprint(operation: InventoryOperation, vararg values: String): String {
        val canonical = buildString {
            append("circulation-inventory-command-v1")
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

    private companion object {
        const val NULL_MARKER = "<null>"
    }

    private data class InventoryMutation(
        val previous: Copy?,
        val current: Copy,
    )
}
