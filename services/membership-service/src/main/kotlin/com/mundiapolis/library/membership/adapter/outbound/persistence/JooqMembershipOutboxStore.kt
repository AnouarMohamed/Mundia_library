package com.mundiapolis.library.membership.adapter.outbound.persistence

import com.mundiapolis.library.membership.dto.ClaimedMembershipOutboxEvent
import com.mundiapolis.library.membership.dto.MembershipBrokerAcknowledgement
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureCode
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureDisposition
import com.mundiapolis.library.membership.dto.MembershipOutboxStatistics
import com.mundiapolis.library.membership.service.MembershipOutboxStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqMembershipOutboxStore(
    private val dsl: DSLContext,
) : MembershipOutboxStore {
    override fun claimBatch(
        owner: String,
        now: Instant,
        leaseExpiresAt: Instant,
        batchSize: Int,
    ): List<ClaimedMembershipOutboxEvent> {
        val leaseToken = UUID.randomUUID()
        return dsl.resultQuery(
            """
            WITH candidates AS (
                SELECT candidate.event_id
                FROM membership_outbox_event AS candidate
                WHERE candidate.published_at IS NULL
                  AND candidate.blocked_at IS NULL
                  AND candidate.next_attempt_at <= CAST(? AS timestamp with time zone)
                  AND (
                      candidate.lease_expires_at IS NULL
                      OR candidate.lease_expires_at <= CAST(? AS timestamp with time zone)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM membership_outbox_event AS earlier
                      WHERE earlier.aggregate_id = candidate.aggregate_id
                        AND earlier.aggregate_version < candidate.aggregate_version
                        AND earlier.published_at IS NULL
                  )
                ORDER BY candidate.next_attempt_at, candidate.created_at, candidate.event_id
                LIMIT CAST(? AS integer)
                FOR UPDATE OF candidate SKIP LOCKED
            )
            UPDATE membership_outbox_event AS event
            SET lease_owner = ?,
                lease_token = CAST(? AS uuid),
                lease_expires_at = CAST(? AS timestamp with time zone),
                delivery_attempts = event.delivery_attempts + 1,
                last_attempt_at = CAST(? AS timestamp with time zone)
            FROM candidates
            WHERE event.event_id = candidates.event_id
            RETURNING event.event_id,
                event.aggregate_id,
                event.aggregate_version,
                event.event_type,
                event.event_version,
                event.occurred_at,
                event.payload::text AS payload_json,
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
        ).fetch { record ->
            ClaimedMembershipOutboxEvent(
                eventId = requireNotNull(record.get("event_id", UUID::class.java)),
                aggregateId = requireNotNull(record.get("aggregate_id", UUID::class.java)),
                aggregateVersion = requireNotNull(record.get("aggregate_version", Long::class.javaObjectType)),
                eventType = requireNotNull(record.get("event_type", String::class.java)),
                eventVersion = requireNotNull(record.get("event_version", Int::class.javaObjectType)),
                occurredAt = requireNotNull(record.get("occurred_at", OffsetDateTime::class.java)).toInstant(),
                payloadJson = requireNotNull(record.get("payload_json", String::class.java)),
                deliveryAttempt = requireNotNull(record.get("delivery_attempts", Int::class.javaObjectType)),
                leaseToken = requireNotNull(record.get("lease_token", UUID::class.java)),
            )
        }
    }

    override fun markPublished(
        owner: String,
        event: ClaimedMembershipOutboxEvent,
        acknowledgement: MembershipBrokerAcknowledgement,
        publishedAt: Instant,
    ): Boolean = dsl.execute(
        """
        UPDATE membership_outbox_event
        SET published_at = CAST(? AS timestamp with time zone),
            broker_topic = ?,
            broker_partition = ?,
            broker_offset = ?,
            lease_owner = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            last_error_code = NULL
        WHERE event_id = CAST(? AS uuid)
          AND published_at IS NULL
          AND blocked_at IS NULL
          AND lease_owner = ?
          AND lease_token = CAST(? AS uuid)
        """.trimIndent(),
        publishedAt.toOffsetDateTime(),
        acknowledgement.topic,
        acknowledgement.partition,
        acknowledgement.offset,
        event.eventId,
        owner,
        event.leaseToken,
    ) == 1

    override fun recordFailure(
        owner: String,
        event: ClaimedMembershipOutboxEvent,
        code: MembershipOutboxFailureCode,
        failedAt: Instant,
        nextAttemptAt: Instant,
        maximumAttempts: Int,
        blockImmediately: Boolean,
    ): MembershipOutboxFailureDisposition {
        val blocked = blockImmediately || event.deliveryAttempt >= maximumAttempts
        val records = dsl.resultQuery(
            """
            UPDATE membership_outbox_event
            SET lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_error_code = ?,
                next_attempt_at = CAST(? AS timestamp with time zone),
                blocked_at = CASE
                    WHEN CAST(? AS boolean) THEN CAST(? AS timestamp with time zone)
                    ELSE NULL
                END
            WHERE event_id = CAST(? AS uuid)
              AND published_at IS NULL
              AND blocked_at IS NULL
              AND lease_owner = ?
              AND lease_token = CAST(? AS uuid)
            RETURNING blocked_at
            """.trimIndent(),
            code.name,
            nextAttemptAt.toOffsetDateTime(),
            blocked,
            failedAt.toOffsetDateTime(),
            event.eventId,
            owner,
            event.leaseToken,
        ).fetch()
        if (records.isEmpty()) return MembershipOutboxFailureDisposition.CLAIM_LOST
        return if (blocked) {
            MembershipOutboxFailureDisposition.BLOCKED
        } else {
            MembershipOutboxFailureDisposition.RETRY_SCHEDULED
        }
    }

    override fun deletePublishedBefore(cutoff: Instant, batchSize: Int): Int = dsl.execute(
        """
        DELETE FROM membership_outbox_event
        WHERE event_id IN (
            SELECT event_id
            FROM membership_outbox_event
            WHERE published_at < CAST(? AS timestamp with time zone)
            ORDER BY published_at, event_id
            LIMIT CAST(? AS integer)
        )
        """.trimIndent(),
        cutoff.toOffsetDateTime(),
        batchSize,
    )

    override fun statistics(now: Instant): MembershipOutboxStatistics {
        val record = dsl.fetchOne(
            """
            SELECT COUNT(*) FILTER (
                       WHERE published_at IS NULL AND blocked_at IS NULL
                   ) AS pending,
                   COUNT(*) FILTER (
                       WHERE published_at IS NULL
                         AND blocked_at IS NULL
                         AND lease_expires_at > CAST(? AS timestamp with time zone)
                   ) AS leased,
                   COUNT(*) FILTER (WHERE blocked_at IS NOT NULL) AS blocked,
                   MIN(created_at) FILTER (
                       WHERE published_at IS NULL AND blocked_at IS NULL
                   ) AS oldest_pending_created_at
            FROM membership_outbox_event
            """.trimIndent(),
            now.toOffsetDateTime(),
        ) ?: error("Membership outbox statistics query returned no row")
        return MembershipOutboxStatistics(
            pending = requireNotNull(record.get("pending", Long::class.javaObjectType)),
            leased = requireNotNull(record.get("leased", Long::class.javaObjectType)),
            blocked = requireNotNull(record.get("blocked", Long::class.javaObjectType)),
            oldestPendingCreatedAt =
                record.get("oldest_pending_created_at", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
