package com.mundiapolis.library.catalog.adapter.outbound.persistence

import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONSUMER_INBOX
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_COPY_AVAILABILITY_PROJECTION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION_AVAILABILITY_PROJECTION
import com.mundiapolis.library.catalog.dto.CirculationCopyEvent
import com.mundiapolis.library.catalog.dto.ConsumerEventDisposition
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqAvailabilityProjectionRepository(
    private val dsl: DSLContext,
) {
    fun lockEdition(editionId: UUID) {
        lockAggregate(editionId, EDITION_LOCK_NAMESPACE)
    }

    fun lockCopy(copyId: UUID) {
        lockAggregate(copyId, COPY_LOCK_NAMESPACE)
    }

    private fun lockAggregate(id: UUID, namespace: Long) {
        dsl.fetch(
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), ?))",
            id.toString(),
            namespace,
        )
    }

    fun findInbox(consumerName: String, eventId: UUID): InboxEntry? = dsl
        .select(
            CATALOG_CONSUMER_INBOX.PAYLOAD_SHA256,
            CATALOG_CONSUMER_INBOX.DISPOSITION,
        )
        .from(CATALOG_CONSUMER_INBOX)
        .where(
            CATALOG_CONSUMER_INBOX.CONSUMER_NAME.eq(consumerName)
                .and(CATALOG_CONSUMER_INBOX.EVENT_ID.eq(eventId)),
        )
        .fetchOne { record ->
            InboxEntry(
                payloadSha256 = requireNotNull(record[CATALOG_CONSUMER_INBOX.PAYLOAD_SHA256]).trim(),
                disposition = ConsumerEventDisposition.valueOf(
                    requireNotNull(record[CATALOG_CONSUMER_INBOX.DISPOSITION]),
                ),
            )
        }

    fun findCopy(copyId: UUID): ProjectedCopy? = dsl
        .selectFrom(CATALOG_COPY_AVAILABILITY_PROJECTION)
        .where(CATALOG_COPY_AVAILABILITY_PROJECTION.COPY_ID.eq(copyId))
        .fetchOne { record ->
            ProjectedCopy(
                editionId = requireNotNull(record.editionId),
                sourceVersion = requireNotNull(record.sourceVersion),
            )
        }

    fun saveCopy(event: CirculationCopyEvent, expectedVersion: Long?, now: Instant): Boolean {
        val nowValue = now.toOffsetDateTime()
        val occurredAt = event.occurredAt.toOffsetDateTime()
        return if (expectedVersion == null) {
            dsl.insertInto(CATALOG_COPY_AVAILABILITY_PROJECTION)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.COPY_ID, event.copyId)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.EDITION_ID, event.editionId)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.STATUS, event.status.name)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.SOURCE_VERSION, event.aggregateVersion)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.SOURCE_EVENT_ID, event.eventId)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.SOURCE_OCCURRED_AT, occurredAt)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.CREATED_AT, nowValue)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.UPDATED_AT, nowValue)
                .onConflictDoNothing()
                .execute() == 1
        } else {
            dsl.update(CATALOG_COPY_AVAILABILITY_PROJECTION)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.STATUS, event.status.name)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.SOURCE_VERSION, event.aggregateVersion)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.SOURCE_EVENT_ID, event.eventId)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.SOURCE_OCCURRED_AT, occurredAt)
                .set(CATALOG_COPY_AVAILABILITY_PROJECTION.UPDATED_AT, nowValue)
                .where(
                    CATALOG_COPY_AVAILABILITY_PROJECTION.COPY_ID.eq(event.copyId)
                        .and(CATALOG_COPY_AVAILABILITY_PROJECTION.EDITION_ID.eq(event.editionId))
                        .and(CATALOG_COPY_AVAILABILITY_PROJECTION.SOURCE_VERSION.eq(expectedVersion)),
                )
                .execute() == 1
        }
    }

    fun recomputeEdition(editionId: UUID, now: Instant) {
        dsl.execute(
            """
            INSERT INTO catalog_edition_availability_projection (
                edition_id, total_copies, available_copies, source_version,
                source_occurred_at, updated_at
            )
            SELECT
                CAST(? AS uuid),
                count(*) FILTER (WHERE status <> 'WITHDRAWN')::integer,
                count(*) FILTER (WHERE status = 'AVAILABLE')::integer,
                max(source_version),
                max(source_occurred_at),
                CAST(? AS timestamptz)
            FROM catalog_copy_availability_projection
            WHERE edition_id = ?
            HAVING count(*) > 0
            ON CONFLICT (edition_id) DO UPDATE SET
                total_copies = EXCLUDED.total_copies,
                available_copies = EXCLUDED.available_copies,
                source_version = EXCLUDED.source_version,
                source_occurred_at = EXCLUDED.source_occurred_at,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            editionId,
            now.toOffsetDateTime(),
            editionId,
        )
    }

    fun appendInbox(
        consumerName: String,
        event: CirculationCopyEvent,
        disposition: ConsumerEventDisposition,
        now: Instant,
    ): Boolean = dsl.insertInto(CATALOG_CONSUMER_INBOX)
        .set(CATALOG_CONSUMER_INBOX.CONSUMER_NAME, consumerName)
        .set(CATALOG_CONSUMER_INBOX.EVENT_ID, event.eventId)
        .set(CATALOG_CONSUMER_INBOX.EVENT_TYPE, event.eventType)
        .set(CATALOG_CONSUMER_INBOX.EVENT_VERSION, event.eventVersion)
        .set(CATALOG_CONSUMER_INBOX.AGGREGATE_TYPE, COPY_AGGREGATE)
        .set(CATALOG_CONSUMER_INBOX.AGGREGATE_ID, event.copyId)
        .set(CATALOG_CONSUMER_INBOX.AGGREGATE_VERSION, event.aggregateVersion)
        .set(CATALOG_CONSUMER_INBOX.PAYLOAD_SHA256, event.payloadSha256)
        .set(CATALOG_CONSUMER_INBOX.DISPOSITION, disposition.name)
        .set(CATALOG_CONSUMER_INBOX.RECEIVED_AT, now.toOffsetDateTime())
        .set(CATALOG_CONSUMER_INBOX.PROCESSED_AT, now.toOffsetDateTime())
        .onConflictDoNothing()
        .execute() == 1

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    data class InboxEntry(
        val payloadSha256: String,
        val disposition: ConsumerEventDisposition,
    )

    data class ProjectedCopy(
        val editionId: UUID,
        val sourceVersion: Long,
    )

    private companion object {
        const val COPY_AGGREGATE = "copy"
        const val EDITION_LOCK_NAMESPACE = 0x43415445L
        const val COPY_LOCK_NAMESPACE = 0x43415443L
    }
}
