package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_CONSUMER_INBOX
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationConsumerInboxRecord
import com.mundiapolis.library.circulation.application.model.EligibilityEventDisposition
import com.mundiapolis.library.circulation.application.model.ProcessedConsumerEvent
import com.mundiapolis.library.circulation.application.port.outbound.ConsumerInboxStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqConsumerInboxStore(
    private val dsl: DSLContext,
) : ConsumerInboxStore {
    override fun find(consumerName: String, eventId: UUID): ProcessedConsumerEvent? = dsl
        .selectFrom(CIRCULATION_CONSUMER_INBOX)
        .where(
            CIRCULATION_CONSUMER_INBOX.CONSUMER_NAME.eq(consumerName)
                .and(CIRCULATION_CONSUMER_INBOX.EVENT_ID.eq(eventId)),
        )
        .fetchOne()
        ?.toModel()

    override fun append(event: ProcessedConsumerEvent): Boolean = dsl
        .insertInto(CIRCULATION_CONSUMER_INBOX)
        .set(CIRCULATION_CONSUMER_INBOX.CONSUMER_NAME, event.consumerName)
        .set(CIRCULATION_CONSUMER_INBOX.EVENT_ID, event.eventId)
        .set(CIRCULATION_CONSUMER_INBOX.EVENT_TYPE, event.eventType)
        .set(CIRCULATION_CONSUMER_INBOX.EVENT_VERSION, event.eventVersion)
        .set(CIRCULATION_CONSUMER_INBOX.AGGREGATE_TYPE, event.aggregateType)
        .set(CIRCULATION_CONSUMER_INBOX.AGGREGATE_ID, event.aggregateId)
        .set(CIRCULATION_CONSUMER_INBOX.AGGREGATE_VERSION, event.aggregateVersion)
        .set(CIRCULATION_CONSUMER_INBOX.PAYLOAD_SHA256, event.payloadFingerprint)
        .set(CIRCULATION_CONSUMER_INBOX.DISPOSITION, event.disposition.name)
        .set(CIRCULATION_CONSUMER_INBOX.RECEIVED_AT, event.receivedAt.toOffsetDateTime())
        .set(CIRCULATION_CONSUMER_INBOX.PROCESSED_AT, event.processedAt.toOffsetDateTime())
        .onConflictDoNothing()
        .execute() == 1

    private fun CirculationConsumerInboxRecord.toModel(): ProcessedConsumerEvent =
        ProcessedConsumerEvent(
            consumerName = requireNotNull(consumerName),
            eventId = requireNotNull(eventId),
            eventType = requireNotNull(eventType),
            eventVersion = requireNotNull(eventVersion),
            aggregateType = requireNotNull(aggregateType),
            aggregateId = requireNotNull(aggregateId),
            aggregateVersion = requireNotNull(aggregateVersion),
            payloadFingerprint = requireNotNull(payloadSha256).trim(),
            disposition = EligibilityEventDisposition.valueOf(requireNotNull(disposition)),
            receivedAt = requireNotNull(receivedAt).toInstant(),
            processedAt = requireNotNull(processedAt).toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
