package com.mundiapolis.library.notification.dto

import java.time.Instant

enum class NotificationCategory {
    DUE_SOON,
    OVERDUE,
    HOLD_READY,
    ACCOUNT_STATUS,
    GENERAL,
}

enum class NotificationReadStatus { ALL, READ, UNREAD }

data class NotificationItem(
    val notificationId: String,
    val category: NotificationCategory,
    val subject: String,
    val body: String,
    val occurredAt: Instant,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class NotificationPage(
    val memberId: String,
    val items: List<NotificationItem>,
    val nextCursor: String?,
)
