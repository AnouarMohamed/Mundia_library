package com.mundiapolis.library.notification.adapter.outbound.persistence

import com.mundiapolis.library.notification.dto.SesFeedbackConflictException
import com.mundiapolis.library.notification.dto.SesFeedbackCorrelationException
import com.mundiapolis.library.notification.dto.SesFeedbackEvent
import com.mundiapolis.library.notification.dto.SesFeedbackExecution
import com.mundiapolis.library.notification.service.SesFeedbackStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
            SELECT provider_outcome, provider_event_at
              FROM notification_delivery
             WHERE delivery_id = ? AND channel = 'EMAIL' AND status = 'DELIVERED'
               AND provider = 'aws-ses' AND provider_message_ref = ?
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
        return SesFeedbackExecution(replayed = false)
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
