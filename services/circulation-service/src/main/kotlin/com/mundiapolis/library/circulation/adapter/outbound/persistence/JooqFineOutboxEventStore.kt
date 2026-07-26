package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.FineOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.FineOutboxEventStore
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqFineOutboxEventStore(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : FineOutboxEventStore {
    override fun append(event: FineOutboxEvent) {
        val result = event.result
        val payload = linkedMapOf<String, Any>(
            "fineId" to result.fineId.value.toString(),
            "loanId" to result.loanId.value.toString(),
            "memberId" to result.memberId.value.toString(),
            "currency" to result.currency,
            "balanceMinor" to result.balanceMinor,
            "status" to result.status.name,
            "fineVersion" to result.version,
            "ledgerEntryId" to result.ledgerEntryId.value.toString(),
            "ledgerEntryType" to result.ledgerEntryType.name,
            "ledgerDeltaMinor" to result.ledgerDeltaMinor,
            "occurredAt" to result.occurredAt.toString(),
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
        check(inserted == 1) { "Fine outbox event was not persisted" }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val AGGREGATE_TYPE = "fine"
    }
}
