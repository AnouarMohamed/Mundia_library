package com.mundiapolis.library.notification.adapter.outbound.persistence

import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_INBOX
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_DELIVERY
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_INTENT_RECEIPT
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_PREFERENCE
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.tables.records.NotificationPreferenceRecord
import com.mundiapolis.library.notification.dto.NotificationChannel
import com.mundiapolis.library.notification.dto.NotificationCategory
import com.mundiapolis.library.notification.dto.NotificationIntentCommand
import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationPreference
import com.mundiapolis.library.notification.dto.NotificationReadStatus
import com.mundiapolis.library.notification.dto.UpdateNotificationPreferenceRequest
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@Repository
class JooqNotificationRepository(private val dsl: DSLContext) {
    fun insertIntentInbox(intent: NotificationIntentCommand, notificationId: UUID, createdAt: Instant): Boolean {
        val timestamp = createdAt.atOffset(ZoneOffset.UTC)
        return dsl.insertInto(NOTIFICATION_INBOX)
            .set(NOTIFICATION_INBOX.NOTIFICATION_ID, notificationId)
            .set(NOTIFICATION_INBOX.MEMBER_ID, intent.memberId)
            .set(NOTIFICATION_INBOX.SOURCE_EVENT_ID, intent.eventId)
            .set(NOTIFICATION_INBOX.SOURCE_TYPE, intent.sourceType)
            .set(NOTIFICATION_INBOX.CATEGORY, intent.category.name)
            .set(NOTIFICATION_INBOX.SUBJECT, intent.subject)
            .set(NOTIFICATION_INBOX.BODY, intent.body)
            .set(NOTIFICATION_INBOX.OCCURRED_AT, intent.occurredAt.atOffset(ZoneOffset.UTC))
            .set(NOTIFICATION_INBOX.CREATED_AT, timestamp)
            .onConflict(NOTIFICATION_INBOX.SOURCE_EVENT_ID)
            .doNothing()
            .execute() == 1
    }

    fun insertDelivery(
        notificationId: UUID,
        channel: NotificationChannel,
        createdAt: Instant,
        suppressed: Boolean = false,
    ) {
        val timestamp = createdAt.atOffset(ZoneOffset.UTC)
        val inApp = channel == NotificationChannel.IN_APP
        val status = when {
            inApp -> "DELIVERED"
            suppressed -> "SUPPRESSED"
            else -> "PENDING"
        }
        dsl.insertInto(NOTIFICATION_DELIVERY)
            .set(NOTIFICATION_DELIVERY.DELIVERY_ID, UUID.randomUUID())
            .set(NOTIFICATION_DELIVERY.NOTIFICATION_ID, notificationId)
            .set(NOTIFICATION_DELIVERY.CHANNEL, channel.name)
            .set(NOTIFICATION_DELIVERY.STATUS, status)
            .set(NOTIFICATION_DELIVERY.ATTEMPT_COUNT, 0)
            .set(NOTIFICATION_DELIVERY.NEXT_ATTEMPT_AT, if (status == "PENDING") timestamp else null)
            .set(NOTIFICATION_DELIVERY.DELIVERED_AT, if (inApp) timestamp else null)
            .set(NOTIFICATION_DELIVERY.CREATED_AT, timestamp)
            .set(NOTIFICATION_DELIVERY.UPDATED_AT, timestamp)
            .execute()
    }

    fun findPreference(memberId: UUID): NotificationPreference? =
        dsl.selectFrom(NOTIFICATION_PREFERENCE)
            .where(NOTIFICATION_PREFERENCE.MEMBER_ID.eq(memberId))
            .fetchOne { it.toPreference() }

    fun insertPreference(
        memberId: UUID,
        request: UpdateNotificationPreferenceRequest,
        updatedAt: Instant,
    ): NotificationPreference? = dsl.insertInto(NOTIFICATION_PREFERENCE)
        .set(NOTIFICATION_PREFERENCE.MEMBER_ID, memberId)
        .set(NOTIFICATION_PREFERENCE.IN_APP_ENABLED, true)
        .set(NOTIFICATION_PREFERENCE.EMAIL_ENABLED, request.emailEnabled)
        .set(NOTIFICATION_PREFERENCE.DUE_SOON_ENABLED, request.dueSoonEnabled)
        .set(NOTIFICATION_PREFERENCE.OVERDUE_ENABLED, request.overdueEnabled)
        .set(NOTIFICATION_PREFERENCE.HOLD_READY_ENABLED, request.holdReadyEnabled)
        .set(NOTIFICATION_PREFERENCE.ACCOUNT_STATUS_ENABLED, request.accountStatusEnabled)
        .set(NOTIFICATION_PREFERENCE.VERSION, 1L)
        .set(NOTIFICATION_PREFERENCE.UPDATED_AT, updatedAt.atOffset(ZoneOffset.UTC))
        .onConflict(NOTIFICATION_PREFERENCE.MEMBER_ID)
        .doNothing()
        .returning()
        .fetchOne()
        ?.toPreference()

    fun updatePreference(
        memberId: UUID,
        expectedVersion: Long,
        request: UpdateNotificationPreferenceRequest,
        updatedAt: Instant,
    ): NotificationPreference? = dsl.update(NOTIFICATION_PREFERENCE)
        .set(NOTIFICATION_PREFERENCE.EMAIL_ENABLED, request.emailEnabled)
        .set(NOTIFICATION_PREFERENCE.DUE_SOON_ENABLED, request.dueSoonEnabled)
        .set(NOTIFICATION_PREFERENCE.OVERDUE_ENABLED, request.overdueEnabled)
        .set(NOTIFICATION_PREFERENCE.HOLD_READY_ENABLED, request.holdReadyEnabled)
        .set(NOTIFICATION_PREFERENCE.ACCOUNT_STATUS_ENABLED, request.accountStatusEnabled)
        .set(NOTIFICATION_PREFERENCE.VERSION, NOTIFICATION_PREFERENCE.VERSION.plus(1L))
        .set(NOTIFICATION_PREFERENCE.UPDATED_AT, updatedAt.atOffset(ZoneOffset.UTC))
        .where(
            NOTIFICATION_PREFERENCE.MEMBER_ID.eq(memberId)
                .and(NOTIFICATION_PREFERENCE.VERSION.eq(expectedVersion)),
        )
        .returning()
        .fetchOne()
        ?.toPreference()

    fun insertIntentReceipt(
        intent: NotificationIntentCommand,
        notificationId: UUID,
        processedAt: Instant,
    ) {
        val timestamp = processedAt.atOffset(ZoneOffset.UTC)
        dsl.insertInto(NOTIFICATION_INTENT_RECEIPT)
            .set(NOTIFICATION_INTENT_RECEIPT.EVENT_ID, intent.eventId)
            .set(NOTIFICATION_INTENT_RECEIPT.NOTIFICATION_ID, notificationId)
            .set(NOTIFICATION_INTENT_RECEIPT.PAYLOAD_SHA256, intent.payloadSha256)
            .set(NOTIFICATION_INTENT_RECEIPT.TOPIC, intent.topic)
            .set(NOTIFICATION_INTENT_RECEIPT.SOURCE_PARTITION, intent.partition)
            .set(NOTIFICATION_INTENT_RECEIPT.SOURCE_OFFSET, intent.offset)
            .set(NOTIFICATION_INTENT_RECEIPT.RECEIVED_AT, timestamp)
            .set(NOTIFICATION_INTENT_RECEIPT.PROCESSED_AT, timestamp)
            .execute()
    }

    fun findIntentReceipt(eventId: UUID): NotificationIntentReceipt? =
        dsl.select(
            NOTIFICATION_INTENT_RECEIPT.NOTIFICATION_ID,
            NOTIFICATION_INTENT_RECEIPT.PAYLOAD_SHA256,
        )
            .from(NOTIFICATION_INTENT_RECEIPT)
            .where(NOTIFICATION_INTENT_RECEIPT.EVENT_ID.eq(eventId))
            .fetchOne { record ->
                NotificationIntentReceipt(
                    notificationId = requireNotNull(record[NOTIFICATION_INTENT_RECEIPT.NOTIFICATION_ID]),
                    payloadSha256 = requireNotNull(record[NOTIFICATION_INTENT_RECEIPT.PAYLOAD_SHA256]),
                )
            }

    fun findPage(
        memberId: UUID,
        status: NotificationReadStatus,
        limit: Int,
        encodedCursor: String?,
    ): NotificationPage {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        val cursor = encodedCursor?.let(::decodeCursor)
        var condition: Condition = NOTIFICATION_INBOX.MEMBER_ID.eq(memberId)
        condition = when (status) {
            NotificationReadStatus.ALL -> condition
            NotificationReadStatus.READ -> condition.and(NOTIFICATION_INBOX.READ_AT.isNotNull)
            NotificationReadStatus.UNREAD -> condition.and(NOTIFICATION_INBOX.READ_AT.isNull)
        }
        cursor?.let {
            condition = condition.and(
                NOTIFICATION_INBOX.CREATED_AT.lt(it.createdAt)
                    .or(
                        NOTIFICATION_INBOX.CREATED_AT.eq(it.createdAt)
                            .and(NOTIFICATION_INBOX.NOTIFICATION_ID.lt(it.notificationId)),
                    ),
            )
        }
        val records = dsl.selectFrom(NOTIFICATION_INBOX)
            .where(condition)
            .orderBy(NOTIFICATION_INBOX.CREATED_AT.desc(), NOTIFICATION_INBOX.NOTIFICATION_ID.desc())
            .limit(limit + 1)
            .fetch()
        val hasMore = records.size > limit
        val items = records.take(limit).map { record ->
            NotificationItem(
                notificationId = requireNotNull(record.notificationId).toString(),
                category = NotificationCategory.valueOf(requireNotNull(record.category)),
                subject = requireNotNull(record.subject),
                body = requireNotNull(record.body),
                occurredAt = requireNotNull(record.occurredAt).toInstant(),
                createdAt = requireNotNull(record.createdAt).toInstant(),
                readAt = record.readAt?.toInstant(),
            )
        }
        val nextCursor = if (hasMore) {
            val last = records[limit - 1]
            encodeCursor(requireNotNull(last.createdAt), requireNotNull(last.notificationId))
        } else null
        return NotificationPage(memberId.toString(), items, nextCursor)
    }

    fun markRead(memberId: UUID, notificationId: UUID, now: Instant): NotificationItem? {
        val timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        val record = dsl.update(NOTIFICATION_INBOX)
            .set(NOTIFICATION_INBOX.READ_AT, DSL.coalesce(NOTIFICATION_INBOX.READ_AT, timestamp))
            .where(
                NOTIFICATION_INBOX.NOTIFICATION_ID.eq(notificationId)
                    .and(NOTIFICATION_INBOX.MEMBER_ID.eq(memberId)),
            )
            .returning()
            .fetchOne() ?: return null
        return NotificationItem(
            requireNotNull(record.notificationId).toString(),
            NotificationCategory.valueOf(requireNotNull(record.category)),
            requireNotNull(record.subject),
            requireNotNull(record.body),
            requireNotNull(record.occurredAt).toInstant(),
            requireNotNull(record.createdAt).toInstant(),
            requireNotNull(record.readAt).toInstant(),
        )
    }

    private fun encodeCursor(createdAt: OffsetDateTime, notificationId: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "${createdAt.toInstant()}|$notificationId".toByteArray(StandardCharsets.UTF_8),
        )

    private fun decodeCursor(value: String): Cursor {
        require(value.length in 1..MAX_CURSOR_LENGTH) { "cursor is invalid" }
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("cursor is invalid") }
        val parts = decoded.split('|')
        require(parts.size == 2) { "cursor is invalid" }
        val instant = runCatching { Instant.parse(parts[0]) }.getOrNull()
            ?: throw IllegalArgumentException("cursor is invalid")
        val id = runCatching { UUID.fromString(parts[1]) }.getOrNull()
            ?: throw IllegalArgumentException("cursor is invalid")
        require(id.toString() == parts[1]) { "cursor is invalid" }
        val canonical = encodeCursor(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC), id)
        require(canonical == value) { "cursor is invalid" }
        return Cursor(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC), id)
    }

    private fun NotificationPreferenceRecord.toPreference(): NotificationPreference =
        NotificationPreference(
            memberId = requireNotNull(memberId).toString(),
            emailEnabled = requireNotNull(emailEnabled),
            dueSoonEnabled = requireNotNull(dueSoonEnabled),
            overdueEnabled = requireNotNull(overdueEnabled),
            holdReadyEnabled = requireNotNull(holdReadyEnabled),
            accountStatusEnabled = requireNotNull(accountStatusEnabled),
            version = requireNotNull(version),
            updatedAt = requireNotNull(updatedAt).toInstant(),
        )

    private data class Cursor(val createdAt: OffsetDateTime, val notificationId: UUID)

    private companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_CURSOR_LENGTH = 128
    }
}

data class NotificationIntentReceipt(
    val notificationId: UUID,
    val payloadSha256: String,
)
