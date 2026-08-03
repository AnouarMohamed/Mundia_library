package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_INVENTORY_AUDIT_ENTRY
import com.mundiapolis.library.circulation.application.model.InventoryAuditEntry
import com.mundiapolis.library.circulation.application.port.outbound.InventoryAuditStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqInventoryAuditStore(
    private val dsl: DSLContext,
) : InventoryAuditStore {
    override fun append(entry: InventoryAuditEntry) {
        val previous = entry.previous
        val result = entry.result
        val inserted = dsl.insertInto(CIRCULATION_INVENTORY_AUDIT_ENTRY)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.ID, entry.id)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.COPY_ID, result.copyId.value)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.COPY_VERSION, result.version)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.OPERATION, entry.operation.name)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.EDITION_ID, result.editionId.value)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.BARCODE, result.barcode)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.PREVIOUS_BRANCH_ID, previous?.branchId?.value)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.BRANCH_ID, result.branchId.value)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.PREVIOUS_STATUS, previous?.status?.name)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.COPY_STATUS, result.status.name)
            .set(
                CIRCULATION_INVENTORY_AUDIT_ENTRY.PREVIOUS_SHELF_LOCATION,
                previous?.shelfLocation,
            )
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.SHELF_LOCATION, result.shelfLocation)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.ACTOR_FINGERPRINT, entry.actorFingerprint)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.REASON, entry.reason)
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.OCCURRED_AT, result.occurredAt.toOffsetDateTime())
            .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.CREATED_AT, result.occurredAt.toOffsetDateTime())
            .execute()
        check(inserted == 1) { "Inventory audit entry was not persisted" }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
