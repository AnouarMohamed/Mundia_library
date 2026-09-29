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

data class NotificationPreference(
    val memberId: String,
    val emailEnabled: Boolean,
    val dueSoonEnabled: Boolean,
    val overdueEnabled: Boolean,
    val holdReadyEnabled: Boolean,
    val accountStatusEnabled: Boolean,
    val version: Long,
    val updatedAt: Instant?,
) {
    fun allowsEmail(category: NotificationCategory): Boolean = emailEnabled && when (category) {
        NotificationCategory.DUE_SOON -> dueSoonEnabled
        NotificationCategory.OVERDUE -> overdueEnabled
        NotificationCategory.HOLD_READY -> holdReadyEnabled
        NotificationCategory.ACCOUNT_STATUS -> accountStatusEnabled
        NotificationCategory.GENERAL -> true
    }
}

data class UpdateNotificationPreferenceRequest(
    val emailEnabled: Boolean,
    val dueSoonEnabled: Boolean,
    val overdueEnabled: Boolean,
    val holdReadyEnabled: Boolean,
    val accountStatusEnabled: Boolean,
)
