package com.mundiapolis.library.notification.adapter.outbound.persistence

import com.mundiapolis.library.notification.dto.ClaimedEmailDelivery
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.mundiapolis.library.notification.dto.EmailDeliveryStatistics
import com.mundiapolis.library.notification.dto.EmailProviderReceipt
import com.mundiapolis.library.notification.service.EmailDeliveryStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqEmailDeliveryStore(private val dsl: DSLContext) : EmailDeliveryStore {
    @Transactional
    override fun claimBatch(
        owner: String,
        leaseToken: UUID,
        now: Instant,
        leaseExpiresAt: Instant,
        batchSize: Int,
        maximumAttempts: Int,
    ): List<ClaimedEmailDelivery> {
        val timestamp = now.utc()
        dsl.execute(
            """
            UPDATE notification_delivery d
               SET status = 'SUPPRESSED',
                   last_error_code = 'RECIPIENT_SUPPRESSED',
                   lease_owner = NULL,
                   lease_token = NULL,
                   lease_expires_at = NULL,
                   next_attempt_at = NULL,
                   updated_at = CAST(? AS TIMESTAMPTZ)
              FROM notification_inbox i
              JOIN notification_email_suppression s ON s.member_id = i.member_id
             WHERE i.notification_id = d.notification_id
               AND d.channel = 'EMAIL'
               AND d.status = 'DELIVERING'
               AND d.lease_expires_at <= CAST(? AS TIMESTAMPTZ)
            """.trimIndent(),
            timestamp,
            timestamp,
        )
        dsl.execute(
            """
            UPDATE notification_delivery
               SET status = 'DEAD_LETTERED',
                   last_error_code = ?,
                   dead_lettered_at = CAST(? AS TIMESTAMPTZ),
                   lease_owner = NULL,
                   lease_token = NULL,
                   lease_expires_at = NULL,
                   next_attempt_at = NULL,
                   updated_at = CAST(? AS TIMESTAMPTZ)
             WHERE channel = 'EMAIL'
               AND status = 'DELIVERING'
               AND lease_expires_at <= CAST(? AS TIMESTAMPTZ)
               AND attempt_count >= ?
            """.trimIndent(),
            EmailDeliveryFailureCode.LEASE_EXPIRED.name,
            timestamp,
            timestamp,
            timestamp,
            maximumAttempts,
        )
        return dsl.fetch(
            """
            WITH candidates AS (
                SELECT delivery_id
                  FROM notification_delivery
                 WHERE channel = 'EMAIL'
                   AND attempt_count < ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM notification_inbox i
                         JOIN notification_email_suppression s ON s.member_id = i.member_id
                        WHERE i.notification_id = notification_delivery.notification_id
                   )
                   AND (
                       (status IN ('PENDING', 'FAILED') AND next_attempt_at <= CAST(? AS TIMESTAMPTZ))
                       OR (status = 'DELIVERING' AND lease_expires_at <= CAST(? AS TIMESTAMPTZ))
                   )
                 ORDER BY COALESCE(next_attempt_at, lease_expires_at), created_at, delivery_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
            ), claimed AS (
                UPDATE notification_delivery d
                   SET status = 'DELIVERING',
                       attempt_count = d.attempt_count + 1,
                       last_attempt_at = CAST(? AS TIMESTAMPTZ),
                       lease_owner = ?,
                       lease_token = ?,
                       lease_expires_at = CAST(? AS TIMESTAMPTZ),
                       next_attempt_at = NULL,
                       last_error_code = NULL,
                       dead_lettered_at = NULL,
                       updated_at = CAST(? AS TIMESTAMPTZ)
                  FROM candidates c
                 WHERE d.delivery_id = c.delivery_id
             RETURNING d.delivery_id, d.notification_id, d.attempt_count, d.lease_token
            )
            SELECT c.delivery_id, c.notification_id, i.member_id, i.subject, i.body,
                   c.attempt_count, c.lease_token
              FROM claimed c
              JOIN notification_inbox i ON i.notification_id = c.notification_id
             ORDER BY c.delivery_id
            """.trimIndent(),
            maximumAttempts,
            timestamp,
            timestamp,
            batchSize,
            timestamp,
            owner,
            leaseToken,
            leaseExpiresAt.utc(),
            timestamp,
        ).map { record ->
            ClaimedEmailDelivery(
                deliveryId = requireNotNull(record.get("delivery_id", UUID::class.java)),
                notificationId = requireNotNull(record.get("notification_id", UUID::class.java)),
                memberId = requireNotNull(record.get("member_id", UUID::class.java)),
                subject = requireNotNull(record.get("subject", String::class.java)),
                body = requireNotNull(record.get("body", String::class.java)),
                attempt = requireNotNull(record.get("attempt_count", Int::class.java)),
                leaseToken = requireNotNull(record.get("lease_token", UUID::class.java)),
            )
        }
    }

    override fun markDelivered(
        owner: String,
        delivery: ClaimedEmailDelivery,
        receipt: EmailProviderReceipt,
        deliveredAt: Instant,
    ): Boolean = dsl.execute(
        """
        UPDATE notification_delivery
           SET status = 'DELIVERED', delivered_at = CAST(? AS TIMESTAMPTZ), provider = ?, provider_message_ref = ?,
               lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
               next_attempt_at = NULL, last_error_code = NULL, updated_at = CAST(? AS TIMESTAMPTZ)
         WHERE delivery_id = ? AND status = 'DELIVERING' AND lease_owner = ? AND lease_token = ?
        """.trimIndent(),
        deliveredAt.utc(), receipt.provider, receipt.messageReference, deliveredAt.utc(),
        delivery.deliveryId, owner, delivery.leaseToken,
    ) == 1

    override fun suppressClaimIfRecipientSuppressed(
        owner: String,
        delivery: ClaimedEmailDelivery,
        suppressedAt: Instant,
    ): Boolean = dsl.execute(
        """
        UPDATE notification_delivery d
           SET status = 'SUPPRESSED',
               lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
               next_attempt_at = NULL, last_error_code = 'RECIPIENT_SUPPRESSED',
               updated_at = CAST(? AS TIMESTAMPTZ)
          FROM notification_inbox i
          JOIN notification_email_suppression s ON s.member_id = i.member_id
         WHERE i.notification_id = d.notification_id
           AND d.delivery_id = ? AND d.status = 'DELIVERING'
           AND d.lease_owner = ? AND d.lease_token = ?
        """.trimIndent(),
        suppressedAt.utc(),
        delivery.deliveryId,
        owner,
        delivery.leaseToken,
    ) == 1

    override fun recordFailure(
        owner: String,
        delivery: ClaimedEmailDelivery,
        failureCode: EmailDeliveryFailureCode,
        failedAt: Instant,
        nextAttemptAt: Instant?,
        deadLetter: Boolean,
    ): Boolean = dsl.execute(
        """
        UPDATE notification_delivery
           SET status = ?, delivered_at = NULL, provider = NULL, provider_message_ref = NULL,
               lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
               next_attempt_at = CAST(? AS TIMESTAMPTZ), last_error_code = ?,
               dead_lettered_at = CAST(? AS TIMESTAMPTZ), updated_at = CAST(? AS TIMESTAMPTZ)
         WHERE delivery_id = ? AND status = 'DELIVERING' AND lease_owner = ? AND lease_token = ?
        """.trimIndent(),
        if (deadLetter) "DEAD_LETTERED" else "FAILED",
        nextAttemptAt?.utc(),
        failureCode.name,
        if (deadLetter) failedAt.utc() else null,
        failedAt.utc(),
        delivery.deliveryId,
        owner,
        delivery.leaseToken,
    ) == 1

    override fun statistics(now: Instant): EmailDeliveryStatistics {
        val record = dsl.fetchOne(
            """
            SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                   count(*) FILTER (WHERE status = 'DELIVERING') AS delivering,
                   count(*) FILTER (WHERE status = 'FAILED') AS retrying,
                   count(*) FILTER (WHERE status = 'DEAD_LETTERED') AS dead_lettered,
                   count(*) FILTER (
                       WHERE status = 'DELIVERING' AND lease_expires_at <= CAST(? AS TIMESTAMPTZ)
                   ) AS expired_leases,
                   min(created_at) FILTER (WHERE status IN ('PENDING', 'FAILED')) AS oldest_pending_at
              FROM notification_delivery
             WHERE channel = 'EMAIL'
            """.trimIndent(),
            now.utc(),
        ) ?: error("Email delivery statistics query returned no row")
        return EmailDeliveryStatistics(
            pending = requireNotNull(record.get("pending", Long::class.java)),
            delivering = requireNotNull(record.get("delivering", Long::class.java)),
            retrying = requireNotNull(record.get("retrying", Long::class.java)),
            deadLettered = requireNotNull(record.get("dead_lettered", Long::class.java)),
            expiredLeases = requireNotNull(record.get("expired_leases", Long::class.java)),
            oldestPendingAt = record.get("oldest_pending_at", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
