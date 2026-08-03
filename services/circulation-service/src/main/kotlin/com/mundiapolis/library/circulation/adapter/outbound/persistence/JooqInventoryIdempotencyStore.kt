package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_INVENTORY_IDEMPOTENCY
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.InventoryCommandResult
import com.mundiapolis.library.circulation.application.model.InventoryOperation
import com.mundiapolis.library.circulation.application.model.StoredInventoryIdempotencyResult
import com.mundiapolis.library.circulation.application.port.outbound.InventoryIdempotencyStore
import com.mundiapolis.library.circulation.domain.model.BranchId
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.CopyStatus
import com.mundiapolis.library.circulation.domain.model.EditionId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqInventoryIdempotencyStore(
    private val dsl: DSLContext,
) : InventoryIdempotencyStore {
    override fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: InventoryOperation,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean = dsl.insertInto(CIRCULATION_INVENTORY_IDEMPOTENCY)
        .set(CIRCULATION_INVENTORY_IDEMPOTENCY.OWNER_FINGERPRINT, owner.fingerprint)
        .set(CIRCULATION_INVENTORY_IDEMPOTENCY.IDEMPOTENCY_KEY, key.value)
        .set(CIRCULATION_INVENTORY_IDEMPOTENCY.OPERATION, operation.name)
        .set(CIRCULATION_INVENTORY_IDEMPOTENCY.REQUEST_FINGERPRINT, requestFingerprint)
        .set(CIRCULATION_INVENTORY_IDEMPOTENCY.CREATED_AT, createdAt.toOffsetDateTime())
        .set(CIRCULATION_INVENTORY_IDEMPOTENCY.EXPIRES_AT, expiresAt.toOffsetDateTime())
        .onConflictDoNothing()
        .execute() == 1

    override fun find(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
    ): StoredInventoryIdempotencyResult? = dsl
        .selectFrom(CIRCULATION_INVENTORY_IDEMPOTENCY)
        .where(
            CIRCULATION_INVENTORY_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                .and(CIRCULATION_INVENTORY_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value)),
        )
        .fetchOne()
        ?.let { record ->
            val operation = InventoryOperation.valueOf(requireNotNull(record.operation))
            val result = record.completedAt?.let {
                InventoryCommandResult(
                    copyId = CopyId(requireNotNull(record.copyId)),
                    editionId = EditionId(requireNotNull(record.editionId)),
                    branchId = BranchId(requireNotNull(record.branchId)),
                    barcode = requireNotNull(record.barcode),
                    status = CopyStatus.valueOf(requireNotNull(record.copyStatus)),
                    shelfLocation = record.shelfLocation,
                    version = requireNotNull(record.copyVersion),
                    occurredAt = requireNotNull(record.occurredAt).toInstant(),
                )
            }
            StoredInventoryIdempotencyResult(
                operation = operation,
                requestFingerprint = requireNotNull(record.requestFingerprint),
                result = result,
            )
        }

    override fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: InventoryOperation,
        result: InventoryCommandResult,
        completedAt: Instant,
    ) {
        val updated = dsl.update(CIRCULATION_INVENTORY_IDEMPOTENCY)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.RESPONSE_STATUS, operation.responseStatus)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.COPY_ID, result.copyId.value)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.EDITION_ID, result.editionId.value)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.BRANCH_ID, result.branchId.value)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.BARCODE, result.barcode)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.COPY_STATUS, result.status.name)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.SHELF_LOCATION, result.shelfLocation)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.COPY_VERSION, result.version)
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.OCCURRED_AT, result.occurredAt.toOffsetDateTime())
            .set(CIRCULATION_INVENTORY_IDEMPOTENCY.COMPLETED_AT, completedAt.toOffsetDateTime())
            .where(
                CIRCULATION_INVENTORY_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                    .and(CIRCULATION_INVENTORY_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value))
                    .and(CIRCULATION_INVENTORY_IDEMPOTENCY.OPERATION.eq(operation.name))
                    .and(CIRCULATION_INVENTORY_IDEMPOTENCY.COMPLETED_AT.isNull),
            )
            .execute()
        check(updated == 1) { "Inventory idempotency result was not persisted" }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
