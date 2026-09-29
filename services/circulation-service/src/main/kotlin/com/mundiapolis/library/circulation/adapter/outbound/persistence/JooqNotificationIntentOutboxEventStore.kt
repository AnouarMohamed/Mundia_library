package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.NotificationIntentOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.NotificationIntentOutboxEventStore
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqNotificationIntentOutboxEventStore(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : NotificationIntentOutboxEventStore {
    override fun append(event: NotificationIntentOutboxEvent) {
        val payload = linkedMapOf(
            "memberId" to event.memberId.toString(),
            "sourceType" to event.sourceType,
            "category" to event.category,
            "subject" to event.subject,
            "body" to event.body,
            "channels" to event.channels.sorted(),
        )
        val inserted = dsl.insertInto(OUTBOX_EVENT)
            .set(OUTBOX_EVENT.ID, event.id)
            .set(OUTBOX_EVENT.EVENT_STREAM, "NOTIFICATION")
            .set(OUTBOX_EVENT.AGGREGATE_TYPE, "notification-intent")
            .set(OUTBOX_EVENT.AGGREGATE_ID, event.id)
            .set(OUTBOX_EVENT.AGGREGATE_VERSION, 0)
            .set(OUTBOX_EVENT.EVENT_TYPE, "notification.intent.requested")
            .set(OUTBOX_EVENT.EVENT_VERSION, 1)
            .set(OUTBOX_EVENT.OCCURRED_AT, event.occurredAt.toOffsetDateTime())
            .set(OUTBOX_EVENT.PAYLOAD, JSON.valueOf(objectMapper.writeValueAsString(payload)))
            .set(
                OUTBOX_EVENT.HEADERS,
                JSON.valueOf(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "contentType" to "application/json",
                            "schema" to "notification.intent.requested.v1",
                        ),
                    ),
                ),
            )
            .set(OUTBOX_EVENT.CREATED_AT, event.occurredAt.toOffsetDateTime())
            .execute()
        check(inserted == 1) { "Notification intent outbox event was not persisted" }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
