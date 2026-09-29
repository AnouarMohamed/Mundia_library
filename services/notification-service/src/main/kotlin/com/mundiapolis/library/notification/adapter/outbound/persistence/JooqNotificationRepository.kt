package com.mundiapolis.library.notification.adapter.outbound.persistence

import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_INBOX
import com.mundiapolis.library.notification.dto.NotificationCategory
import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationReadStatus
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

    private data class Cursor(val createdAt: OffsetDateTime, val notificationId: UUID)

    private companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_CURSOR_LENGTH = 128
    }
}
