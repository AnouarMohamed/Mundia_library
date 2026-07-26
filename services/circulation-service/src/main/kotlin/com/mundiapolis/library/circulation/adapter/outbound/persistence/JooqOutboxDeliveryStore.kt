package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.application.model.BrokerPublishAcknowledgement
import com.mundiapolis.library.circulation.application.model.ClaimedOutboxEvent
import com.mundiapolis.library.circulation.application.model.OutboxDeliveryStatistics
import com.mundiapolis.library.circulation.application.model.OutboxFailureCode
import com.mundiapolis.library.circulation.application.model.OutboxFailureDisposition
import com.mundiapolis.library.circulation.application.port.outbound.OutboxDeliveryStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqOutboxDeliveryStore(
    private val dsl: DSLContext,
) : OutboxDeliveryStore {
    override fun claimBatch(
        owner: String,
        now: Instant,
        leaseExpiresAt: Instant,
        batchSize: Int,
    ): List<ClaimedOutboxEvent> {
        val leaseToken = UUID.randomUUID()
        val records = dsl.resultQuery(
            """
            WITH candidates AS (
                SELECT candidate.id
                FROM outbox_event AS candidate
                WHERE candidate.published_at IS NULL
                  AND candidate.blocked_at IS NULL
                  AND candidate.next_attempt_at <= ?
                  AND (
                      candidate.lease_expires_at IS NULL
                      OR candidate.lease_expires_at <= ?
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM outbox_event AS earlier
                      WHERE earlier.aggregate_type = candidate.aggregate_type
                        AND earlier.aggregate_id = candidate.aggregate_id
                        AND earlier.aggregate_version < candidate.aggregate_version
                        AND earlier.published_at IS NULL
                  )
                ORDER BY candidate.next_attempt_at, candidate.created_at, candidate.id
                LIMIT ?
                FOR UPDATE OF candidate SKIP LOCKED
            )
            UPDATE outbox_event AS event
            SET lease_owner = ?,
                lease_token = ?,
                lease_expires_at = ?,
                delivery_attempts = event.delivery_attempts + 1,
                last_attempt_at = ?
            FROM candidates
            WHERE event.id = candidates.id
            RETURNING
                event.id,
                event.aggregate_type,
                event.aggregate_id,
                event.aggregate_version,
                event.event_type,
                event.event_version,
                event.occurred_at,
                event.trace_id,
                event.payload::text AS payload_json,
                event.created_at,
                event.delivery_attempts,
                event.lease_token
            """.trimIndent(),
            now.toOffsetDateTime(),
            now.toOffsetDateTime(),
            batchSize,
            owner,
            leaseToken,
            leaseExpiresAt.toOffsetDateTime(),
            now.toOffsetDateTime(),
        ).fetch()

        return records.map { record ->
            ClaimedOutboxEvent(
                id = requireNotNull(record.get("id", UUID::class.java)),
                aggregateType = requireNotNull(record.get("aggregate_type", String::class.java)),
                aggregateId = requireNotNull(record.get("aggregate_id", UUID::class.java)),
                aggregateVersion =
                    requireNotNull(record.get("aggregate_version", Long::class.javaObjectType)),
                eventType = requireNotNull(record.get("event_type", String::class.java)),
                eventVersion =
                    requireNotNull(record.get("event_version", Int::class.javaObjectType)),
                occurredAt =
                    requireNotNull(record.get("occurred_at", OffsetDateTime::class.java)).toInstant(),
                traceId = record.get("trace_id", String::class.java),
                payloadJson = requireNotNull(record.get("payload_json", String::class.java)),
                createdAt =
                    requireNotNull(record.get("created_at", OffsetDateTime::class.java)).toInstant(),
                deliveryAttempt =
                    requireNotNull(record.get("delivery_attempts", Int::class.javaObjectType)),
                leaseToken = requireNotNull(record.get("lease_token", UUID::class.java)),
            )
        }
    }

    override fun markPublished(
        owner: String,
        event: ClaimedOutboxEvent,
        acknowledgement: BrokerPublishAcknowledgement,
        publishedAt: Instant,
    ): Boolean =
        dsl.execute(
            """
            UPDATE outbox_event
            SET published_at = ?,
                broker_topic = ?,
                broker_partition = ?,
                broker_offset = ?,
                lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_error_code = NULL
            WHERE id = ?
              AND published_at IS NULL
              AND blocked_at IS NULL
              AND lease_owner = ?
              AND lease_token = ?
            """.trimIndent(),
            publishedAt.toOffsetDateTime(),
            acknowledgement.topic,
            acknowledgement.partition,
            acknowledgement.offset,
            event.id,
            owner,
            event.leaseToken,
        ) == 1

    override fun recordFailure(
        owner: String,
        event: ClaimedOutboxEvent,
        code: OutboxFailureCode,
        failedAt: Instant,
        nextAttemptAt: Instant,
        maximumAttempts: Int,
        blockImmediately: Boolean,
    ): OutboxFailureDisposition {
        val blocked = blockImmediately || event.deliveryAttempt >= maximumAttempts
        val records = dsl.resultQuery(
            """
            UPDATE outbox_event
            SET lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_error_code = ?,
                next_attempt_at = ?,
                blocked_at = CASE WHEN ? THEN ? ELSE NULL END
            WHERE id = ?
              AND published_at IS NULL
              AND blocked_at IS NULL
              AND lease_owner = ?
              AND lease_token = ?
            RETURNING blocked_at
            """.trimIndent(),
            code.name,
            nextAttemptAt.toOffsetDateTime(),
            blocked,
            failedAt.toOffsetDateTime(),
            event.id,
            owner,
            event.leaseToken,
        ).fetch()
        if (records.isEmpty()) {
            return OutboxFailureDisposition.CLAIM_LOST
        }
        return if (blocked) {
            OutboxFailureDisposition.BLOCKED
        } else {
            OutboxFailureDisposition.RETRY_SCHEDULED
        }
    }

    override fun deletePublishedBefore(cutoff: Instant, batchSize: Int): Int =
        dsl.execute(
            """
            DELETE FROM outbox_event
            WHERE id IN (
                SELECT id
                FROM outbox_event
                WHERE published_at < ?
                ORDER BY published_at, id
                LIMIT ?
            )
            """.trimIndent(),
            cutoff.toOffsetDateTime(),
            batchSize,
        )

    override fun statistics(now: Instant): OutboxDeliveryStatistics {
        val record = dsl.fetchOne(
            """
            SELECT
                COUNT(*) FILTER (
                    WHERE published_at IS NULL AND blocked_at IS NULL
                ) AS pending,
                COUNT(*) FILTER (
                    WHERE published_at IS NULL
                      AND blocked_at IS NULL
                      AND lease_expires_at > ?
                ) AS leased,
                COUNT(*) FILTER (WHERE blocked_at IS NOT NULL) AS blocked,
                MIN(created_at) FILTER (
                    WHERE published_at IS NULL AND blocked_at IS NULL
                ) AS oldest_pending_created_at
            FROM outbox_event
            """.trimIndent(),
            now.toOffsetDateTime(),
        ) ?: error("Outbox statistics query returned no row")

        return OutboxDeliveryStatistics(
            pending = requireNotNull(record.get("pending", Long::class.javaObjectType)),
            leased = requireNotNull(record.get("leased", Long::class.javaObjectType)),
            blocked = requireNotNull(record.get("blocked", Long::class.javaObjectType)),
            oldestPendingCreatedAt =
                record.get("oldest_pending_created_at", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
