package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.InventoryOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.InventoryOutboxEventStore
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqInventoryOutboxEventStore(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : InventoryOutboxEventStore {
    override fun append(event: InventoryOutboxEvent) {
        val result = event.result
        val payload = linkedMapOf<String, Any?>(
            "copyId" to result.copyId.value.toString(),
            "editionId" to result.editionId.value.toString(),
            "branchId" to result.branchId.value.toString(),
            "barcode" to result.barcode,
            "status" to result.status.name,
            "shelfLocation" to result.shelfLocation,
            "copyVersion" to result.version,
            "actorFingerprint" to event.actorFingerprint,
            "reason" to event.reason,
        )
        val headers = mapOf(
            "contentType" to "application/json",
            "schema" to "${event.eventType}.v${event.eventVersion}",
        )
        val inserted = dsl.insertInto(OUTBOX_EVENT)
            .set(OUTBOX_EVENT.ID, event.id)
            .set(OUTBOX_EVENT.AGGREGATE_TYPE, AGGREGATE_TYPE)
            .set(OUTBOX_EVENT.AGGREGATE_ID, event.aggregateId.value)
            .set(OUTBOX_EVENT.AGGREGATE_VERSION, event.aggregateVersion)
            .set(OUTBOX_EVENT.EVENT_TYPE, event.eventType)
            .set(OUTBOX_EVENT.EVENT_VERSION, event.eventVersion)
            .set(OUTBOX_EVENT.OCCURRED_AT, event.occurredAt.toOffsetDateTime())
            .set(OUTBOX_EVENT.PAYLOAD, JSON.valueOf(objectMapper.writeValueAsString(payload)))
            .set(OUTBOX_EVENT.HEADERS, JSON.valueOf(objectMapper.writeValueAsString(headers)))
            .set(OUTBOX_EVENT.CREATED_AT, event.occurredAt.toOffsetDateTime())
            .execute()
        check(inserted == 1) { "Inventory outbox event was not persisted" }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val AGGREGATE_TYPE = "copy"
    }
}
