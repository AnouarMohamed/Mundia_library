package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.BranchId
import com.mundiapolis.library.circulation.domain.model.Copy
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.CopyStatus
import com.mundiapolis.library.circulation.domain.model.EditionId
import java.time.Instant
import java.util.UUID

enum class InventoryOperation(
    val responseStatus: Int,
    val eventType: String,
) {
    REGISTER_COPY(201, "circulation.copy.registered"),
    CHANGE_COPY_CONDITION(200, "circulation.copy.condition-changed"),
    RELOCATE_COPY(200, "circulation.copy.relocated"),
}

data class InventoryCommandResult(
    val copyId: CopyId,
    val editionId: EditionId,
    val branchId: BranchId,
    val barcode: String,
    val status: CopyStatus,
    val shelfLocation: String?,
    val version: Long,
    val occurredAt: Instant,
) {
    companion object {
        fun from(copy: Copy, occurredAt: Instant): InventoryCommandResult = InventoryCommandResult(
            copyId = copy.id,
            editionId = copy.editionId,
            branchId = copy.branchId,
            barcode = copy.barcode.value,
            status = copy.status,
            shelfLocation = copy.shelfLocation?.value,
            version = copy.version,
            occurredAt = occurredAt,
        )
    }
}

data class InventoryCommandExecution(
    val result: InventoryCommandResult,
    val replayed: Boolean,
)

data class StoredInventoryIdempotencyResult(
    val operation: InventoryOperation,
    val requestFingerprint: String,
    val result: InventoryCommandResult?,
)

data class InventoryOutboxEvent(
    val id: UUID,
    val aggregateId: CopyId,
    val aggregateVersion: Long,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val result: InventoryCommandResult,
    val actorFingerprint: String,
    val reason: String,
)

data class InventoryAuditEntry(
    val id: UUID,
    val operation: InventoryOperation,
    val previous: InventoryCommandResult?,
    val result: InventoryCommandResult,
    val actorFingerprint: String,
    val reason: String,
)

class InvalidInventoryInputException(message: String) : CirculationCommandException(message)

class CopyNotFoundException(id: CopyId) :
    CirculationCommandException("Copy ${id.value} was not found")

class CopyAlreadyExistsException :
    CirculationCommandException("Copy identifier or barcode is already registered")

class CopyStateConflictException(message: String) : CirculationCommandException(message)

class ConcurrentInventoryUpdateException :
    CirculationCommandException("The copy changed concurrently; retry the command")
