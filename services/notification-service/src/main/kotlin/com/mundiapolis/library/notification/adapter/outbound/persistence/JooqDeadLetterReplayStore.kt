package com.mundiapolis.library.notification.adapter.outbound.persistence

import com.mundiapolis.library.notification.dto.DeadLetterReplay
import com.mundiapolis.library.notification.service.DeadLetterDeliveryNotFoundException
import com.mundiapolis.library.notification.service.DeadLetterDeliveryStateConflictException
import com.mundiapolis.library.notification.service.DeadLetterRecipientSuppressedException
import com.mundiapolis.library.notification.service.DeadLetterReplayIdempotencyConflictException
import com.mundiapolis.library.notification.service.DeadLetterReplayStore
import com.mundiapolis.library.notification.service.DeadLetterReplayUnsafeFailureException
import com.mundiapolis.library.notification.service.DeadLetterReplayLimitExceededException
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
class JooqDeadLetterReplayStore(private val dsl: DSLContext) : DeadLetterReplayStore {
    @Transactional
    override fun replay(
        requestId: UUID,
        deliveryId: UUID,
        actorSubject: String,
        justification: String,
        queuedAt: Instant,
    ): DeadLetterReplay {
        val requestDigest = digest(deliveryId, actorSubject, justification)
        val durableQueuedAt = queuedAt.truncatedTo(ChronoUnit.MICROS)
        lockReplayRequest(requestId)
        existing(requestId)?.let { return replayResult(it, requestDigest) }

        val delivery = dsl.fetchOne(
            """
            SELECT d.status, d.attempt_count, d.last_error_code, d.dead_lettered_at,
                   d.replay_count, i.member_id
              FROM notification_delivery d
              JOIN notification_inbox i ON i.notification_id = d.notification_id
             WHERE d.delivery_id = ? AND d.channel = 'EMAIL'
             FOR UPDATE OF d
            """.trimIndent(),
            deliveryId,
        ) ?: throw DeadLetterDeliveryNotFoundException()
        if (delivery.get("status", String::class.java) != "DEAD_LETTERED") {
            throw DeadLetterDeliveryStateConflictException()
        }
        val previousFailure = requireNotNull(delivery.get("last_error_code", String::class.java))
        if (previousFailure in AMBIGUOUS_FAILURE_CODES) throw DeadLetterReplayUnsafeFailureException()
        val previousReplayCount = requireNotNull(delivery.get("replay_count", Int::class.java))
        if (previousReplayCount >= MAX_REPLAY_COUNT) throw DeadLetterReplayLimitExceededException()
        val memberId = requireNotNull(delivery.get("member_id", UUID::class.java))
        lockMemberNotificationState(memberId)
        if (dsl.fetchExists(
                dsl.selectOne().from("notification_email_suppression").where("member_id = ?", memberId),
            )
        ) {
            throw DeadLetterRecipientSuppressedException()
        }

        val replayCount = previousReplayCount + 1
        dsl.execute(
            """
            INSERT INTO notification_email_dead_letter_replay_audit (
                request_id, delivery_id, actor_subject, justification, request_sha256,
                previous_attempt_count, previous_last_error_code, previous_dead_lettered_at,
                replay_count, queued_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, CAST(? AS TIMESTAMPTZ))
            """.trimIndent(),
            requestId,
            deliveryId,
            actorSubject,
            justification,
            requestDigest,
            requireNotNull(delivery.get("attempt_count", Int::class.java)),
            previousFailure,
            requireNotNull(delivery.get("dead_lettered_at", OffsetDateTime::class.java)),
            replayCount,
            durableQueuedAt.utc(),
        )
        dsl.execute(
            """
            UPDATE notification_delivery
               SET status = 'PENDING', attempt_count = 0, replay_count = ?,
                   delivered_at = NULL, provider = NULL, provider_message_ref = NULL,
                   lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                   last_attempt_at = NULL,
                   next_attempt_at = CAST(? AS TIMESTAMPTZ), last_error_code = NULL,
                   dead_lettered_at = NULL, updated_at = CAST(? AS TIMESTAMPTZ)
             WHERE delivery_id = ? AND status = 'DEAD_LETTERED'
            """.trimIndent(),
            replayCount,
            durableQueuedAt.utc(),
            durableQueuedAt.utc(),
            deliveryId,
        ).also { updated -> check(updated == 1) { "Dead-letter replay lost its delivery lock" } }
        return DeadLetterReplay(requestId.toString(), deliveryId.toString(), replayCount, durableQueuedAt)
    }

    private fun existing(requestId: UUID): Record? = dsl.fetchOne(
        """
        SELECT request_id, delivery_id, request_sha256, replay_count, queued_at
          FROM notification_email_dead_letter_replay_audit
         WHERE request_id = ?
        """.trimIndent(),
        requestId,
    )

    private fun replayResult(record: Record, requestDigest: String): DeadLetterReplay {
        if (record.get("request_sha256", String::class.java) != requestDigest) {
            throw DeadLetterReplayIdempotencyConflictException()
        }
        return DeadLetterReplay(
            requireNotNull(record.get("request_id", UUID::class.java)).toString(),
            requireNotNull(record.get("delivery_id", UUID::class.java)).toString(),
            requireNotNull(record.get("replay_count", Int::class.java)),
            requireNotNull(record.get("queued_at", OffsetDateTime::class.java)).toInstant(),
        )
    }

    private fun lockReplayRequest(requestId: UUID) {
        dsl.fetchValue("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS TEXT), 2))", requestId)
    }

    private fun lockMemberNotificationState(memberId: UUID) {
        dsl.fetchValue("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS TEXT), 0))", memberId)
    }

    private fun digest(deliveryId: UUID, actorSubject: String, justification: String): String {
        val canonical = "${deliveryId}\u0000${actorSubject}\u0000${justification}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val MAX_REPLAY_COUNT = 3
        val AMBIGUOUS_FAILURE_CODES = setOf("LEASE_EXPIRED", "INTERNAL")
    }
}
