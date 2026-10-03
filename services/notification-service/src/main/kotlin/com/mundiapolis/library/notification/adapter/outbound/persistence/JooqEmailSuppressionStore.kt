package com.mundiapolis.library.notification.adapter.outbound.persistence

import com.mundiapolis.library.notification.dto.EmailSuppressionReason
import com.mundiapolis.library.notification.dto.EmailSuppressionRemoval
import com.mundiapolis.library.notification.service.EmailSuppressionRemovalConflictException
import com.mundiapolis.library.notification.service.EmailSuppressionStore
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@Repository
class JooqEmailSuppressionStore(private val dsl: DSLContext) : EmailSuppressionStore {
    @Transactional
    override fun remove(
        requestId: UUID,
        memberId: UUID,
        actorSubject: String,
        justification: String,
        performedAt: Instant,
    ): EmailSuppressionRemoval {
        val requestDigest = digest(memberId, actorSubject, justification)
        val durablePerformedAt = performedAt.truncatedTo(ChronoUnit.MICROS)
        lockRemovalRequest(requestId)
        existing(requestId)?.let { return replay(it, requestDigest) }
        lockMemberNotificationState(memberId)
        val suppression = dsl.fetchOne(
            """
            SELECT reason, source_delivery_id, source_sns_message_id, source_event_at, suppressed_at
              FROM notification_email_suppression
             WHERE member_id = ?
             FOR UPDATE
            """.trimIndent(),
            memberId,
        )
        val removed = suppression != null
        dsl.execute(
            """
            INSERT INTO notification_email_suppression_removal_audit (
                request_id, member_id, actor_subject, justification, request_sha256, removed,
                previous_reason, previous_source_delivery_id, previous_source_sns_message_id,
                previous_source_event_at, previous_suppressed_at, performed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ))
            """.trimIndent(),
            requestId,
            memberId,
            actorSubject,
            justification,
            requestDigest,
            removed,
            suppression?.get("reason", String::class.java),
            suppression?.get("source_delivery_id", UUID::class.java),
            suppression?.get("source_sns_message_id", UUID::class.java),
            suppression?.get("source_event_at", OffsetDateTime::class.java),
            suppression?.get("suppressed_at", OffsetDateTime::class.java),
            durablePerformedAt.utc(),
        )
        if (removed) {
            dsl.execute("DELETE FROM notification_email_suppression WHERE member_id = ?", memberId)
        }
        return result(requestId, memberId, suppression?.get("reason", String::class.java), removed, durablePerformedAt)
    }

    private fun existing(requestId: UUID): Record? = dsl.fetchOne(
        """
        SELECT request_id, member_id, request_sha256, removed, previous_reason, performed_at
          FROM notification_email_suppression_removal_audit
         WHERE request_id = ?
        """.trimIndent(),
        requestId,
    )

    private fun replay(record: Record, requestDigest: String): EmailSuppressionRemoval {
        if (record.get("request_sha256", String::class.java) != requestDigest) {
            throw EmailSuppressionRemovalConflictException()
        }
        return result(
            record.get("request_id", UUID::class.java) ?: error("request_id was not selected"),
            requireNotNull(record.get("member_id", UUID::class.java)),
            record.get("previous_reason", String::class.java),
            requireNotNull(record.get("removed", Boolean::class.java)),
            requireNotNull(record.get("performed_at", OffsetDateTime::class.java)).toInstant(),
        )
    }

    private fun result(
        requestId: UUID,
        memberId: UUID,
        previousReason: String?,
        removed: Boolean,
        performedAt: Instant,
    ) = EmailSuppressionRemoval(
        requestId.toString(),
        memberId.toString(),
        removed,
        previousReason?.let(EmailSuppressionReason::valueOf),
        performedAt,
    )

    private fun lockMemberNotificationState(memberId: UUID) {
        dsl.fetchValue("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS TEXT), 0))", memberId)
    }

    private fun lockRemovalRequest(requestId: UUID) {
        dsl.fetchValue("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS TEXT), 1))", requestId)
    }

    private fun digest(memberId: UUID, actorSubject: String, justification: String): String {
        val canonical = "${memberId}\u0000${actorSubject}\u0000${justification}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
