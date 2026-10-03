package com.mundiapolis.library.notification.adapter.outbound.persistence

import com.mundiapolis.library.notification.dto.SesFeedbackConflictException
import com.mundiapolis.library.notification.dto.SesFeedbackCorrelationException
import com.mundiapolis.library.notification.dto.SesFeedbackEvent
import com.mundiapolis.library.notification.dto.SesFeedbackExecution
import com.mundiapolis.library.notification.dto.EmailSuppressionReason
import com.mundiapolis.library.notification.service.SesFeedbackStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqSesFeedbackStore(private val dsl: DSLContext) : SesFeedbackStore {
    @Transactional
    override fun apply(event: SesFeedbackEvent, receivedAt: Instant): SesFeedbackExecution {
        val existing = dsl.fetchOne(
            "SELECT payload_sha256 FROM notification_email_feedback_receipt WHERE sns_message_id = ?",
            event.snsMessageId,
        )
        if (existing != null) {
            if (existing.get("payload_sha256", String::class.java) != event.payloadSha256) {
                throw SesFeedbackConflictException()
            }
            return SesFeedbackExecution(replayed = true)
        }

        val delivery = dsl.fetchOne(
            """
            SELECT d.provider_outcome, d.provider_event_at, i.member_id
              FROM notification_delivery d
              JOIN notification_inbox i ON i.notification_id = d.notification_id
             WHERE d.delivery_id = ? AND d.channel = 'EMAIL' AND d.status = 'DELIVERED'
               AND d.provider = 'aws-ses' AND d.provider_message_ref = ?
             FOR UPDATE
            """.trimIndent(),
            event.deliveryId,
            event.providerMessageReference,
        ) ?: throw SesFeedbackCorrelationException()

        val inserted = dsl.execute(
            """
            INSERT INTO notification_email_feedback_receipt (
                sns_message_id, delivery_id, provider_message_ref, event_type,
                event_at, payload_sha256, received_at
            ) VALUES (?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, CAST(? AS TIMESTAMPTZ))
            ON CONFLICT (sns_message_id) DO NOTHING
            """.trimIndent(),
            event.snsMessageId,
            event.deliveryId,
            event.providerMessageReference,
            event.type.name,
            event.eventAt.utc(),
            event.payloadSha256,
            receivedAt.utc(),
        )
        if (inserted == 0) {
            val committedDigest = dsl.fetchValue(
                "SELECT payload_sha256 FROM notification_email_feedback_receipt WHERE sns_message_id = ?",
                event.snsMessageId,
                String::class.java,
            )
            if (committedDigest != event.payloadSha256) throw SesFeedbackConflictException()
            return SesFeedbackExecution(replayed = true)
        }

        val currentOutcome = delivery.get("provider_outcome", String::class.java)
        val currentEventAt = delivery.get("provider_event_at", OffsetDateTime::class.java)?.toInstant()
        val currentPrecedence = currentOutcome?.let(PROVIDER_OUTCOME_PRECEDENCE::getValue) ?: -1
        if (event.type.precedence > currentPrecedence ||
            (event.type.precedence == currentPrecedence && (currentEventAt == null || event.eventAt > currentEventAt))
        ) {
            dsl.execute(
                """
                UPDATE notification_delivery
                   SET provider_outcome = ?, provider_event_at = CAST(? AS TIMESTAMPTZ),
                       updated_at = GREATEST(updated_at, CAST(? AS TIMESTAMPTZ))
                 WHERE delivery_id = ?
                """.trimIndent(),
                event.type.providerOutcome,
                event.eventAt.utc(),
                receivedAt.utc(),
                event.deliveryId,
            )
        }
        event.suppressionReason?.let { reason ->
            suppressRecipient(
                requireNotNull(delivery.get("member_id", UUID::class.java)),
                event,
                reason,
                receivedAt,
            )
        }
        return SesFeedbackExecution(replayed = false)
    }

    private fun suppressRecipient(
        memberId: UUID,
        event: SesFeedbackEvent,
        reason: EmailSuppressionReason,
        receivedAt: Instant,
    ) {
        val suppressedAt = if (receivedAt >= event.eventAt) receivedAt else event.eventAt
        lockMemberNotificationState(memberId)
        dsl.execute(
            """
            INSERT INTO notification_email_suppression (
                member_id, reason, source_delivery_id, source_sns_message_id,
                source_event_at, suppressed_at, updated_at
            ) VALUES (?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ))
            ON CONFLICT (member_id) DO UPDATE
               SET reason = EXCLUDED.reason,
                   source_delivery_id = EXCLUDED.source_delivery_id,
                   source_sns_message_id = EXCLUDED.source_sns_message_id,
                   source_event_at = EXCLUDED.source_event_at,
                   updated_at = EXCLUDED.updated_at
             WHERE CASE notification_email_suppression.reason
                       WHEN 'PERMANENT_BOUNCE' THEN 10
                       WHEN 'COMPLAINT' THEN 20
                   END < ?
                OR (
                    notification_email_suppression.reason = EXCLUDED.reason
                    AND notification_email_suppression.source_event_at < EXCLUDED.source_event_at
                )
            """.trimIndent(),
            memberId,
            reason.name,
            event.deliveryId,
            event.snsMessageId,
            event.eventAt.utc(),
            suppressedAt.utc(),
            suppressedAt.utc(),
            reason.precedence,
        )
        dsl.execute(
            """
            UPDATE notification_delivery d
               SET status = 'SUPPRESSED', next_attempt_at = NULL,
                   last_error_code = 'RECIPIENT_SUPPRESSED', updated_at = CAST(? AS TIMESTAMPTZ)
              FROM notification_inbox i
             WHERE i.notification_id = d.notification_id
               AND i.member_id = ?
               AND d.channel = 'EMAIL'
               AND d.status IN ('PENDING', 'FAILED')
            """.trimIndent(),
            suppressedAt.utc(),
            memberId,
        )
    }

    private fun lockMemberNotificationState(memberId: UUID) {
        dsl.fetchValue(
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS TEXT), 0))",
            memberId,
        )
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        val PROVIDER_OUTCOME_PRECEDENCE = mapOf(
            "SENT" to 10,
            "DELAYED" to 20,
            "DELIVERED" to 30,
            "REJECTED" to 40,
            "RENDERING_FAILED" to 40,
            "BOUNCED" to 50,
            "COMPLAINED" to 60,
        )
    }
}
