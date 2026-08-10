package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.ReservationOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.ReservationOutboxEventStore
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqReservationOutboxEventStore(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : ReservationOutboxEventStore {
    override fun append(event: ReservationOutboxEvent) {
        val result = event.result
        val payload = linkedMapOf<String, Any?>(
            "reservationId" to result.reservationId.value.toString(),
            "memberId" to result.memberId.value.toString(),
            "editionId" to result.editionId.value.toString(),
            "copyId" to result.copyId?.value?.toString(),
            "status" to result.status.name,
            "placedAt" to result.placedAt.toString(),
            "readyAt" to result.readyAt?.toString(),
            "expiresAt" to result.expiresAt?.toString(),
            "fulfilledAt" to result.fulfilledAt?.toString(),
            "cancelledAt" to result.cancelledAt?.toString(),
            "reservationVersion" to result.version,
            "actorFingerprint" to event.actorFingerprint,
        )
        append(
            id = event.id,
            aggregateId = result.reservationId.value,
            aggregateVersion = result.version,
            eventType = event.eventType,
            occurredAt = event.occurredAt,
            payload = payload,
        )
    }

    private fun append(
        id: java.util.UUID,
        aggregateId: java.util.UUID,
        aggregateVersion: Long,
        eventType: String,
        occurredAt: Instant,
        payload: Map<String, Any?>,
    ) {
        val inserted = dsl.insertInto(OUTBOX_EVENT)
            .set(OUTBOX_EVENT.ID, id)
            .set(OUTBOX_EVENT.AGGREGATE_TYPE, "reservation")
            .set(OUTBOX_EVENT.AGGREGATE_ID, aggregateId)
            .set(OUTBOX_EVENT.AGGREGATE_VERSION, aggregateVersion)
            .set(OUTBOX_EVENT.EVENT_TYPE, eventType)
            .set(OUTBOX_EVENT.EVENT_VERSION, 1)
            .set(OUTBOX_EVENT.OCCURRED_AT, occurredAt.toOffsetDateTime())
            .set(OUTBOX_EVENT.PAYLOAD, JSON.valueOf(objectMapper.writeValueAsString(payload)))
            .set(
                OUTBOX_EVENT.HEADERS,
                JSON.valueOf(
                    objectMapper.writeValueAsString(
                        mapOf("contentType" to "application/json", "schema" to "$eventType.v1"),
                    ),
                ),
            )
            .set(OUTBOX_EVENT.CREATED_AT, occurredAt.toOffsetDateTime())
            .execute()
        check(inserted == 1) { "Reservation outbox event was not persisted" }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
