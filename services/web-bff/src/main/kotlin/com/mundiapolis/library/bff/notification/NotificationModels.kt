package com.mundiapolis.library.bff.notification

import java.time.Instant
import java.util.UUID

enum class NotificationReadStatusView { ALL, READ, UNREAD }

enum class NotificationCategoryView { DUE_SOON, OVERDUE, HOLD_READY, ACCOUNT_STATUS, GENERAL }

data class NotificationItemView(
    val notificationId: UUID,
    val category: NotificationCategoryView,
    val subject: String,
    val body: String,
    val occurredAt: Instant,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class NotificationPageView(
    val memberId: UUID,
    val items: List<NotificationItemView>,
    val nextCursor: String?,
)

data class NotificationPreferenceView(
    val memberId: UUID,
    val emailEnabled: Boolean,
    val dueSoonEnabled: Boolean,
    val overdueEnabled: Boolean,
    val holdReadyEnabled: Boolean,
    val accountStatusEnabled: Boolean,
    val version: Long,
    val updatedAt: Instant?,
)

data class UpdateNotificationPreferenceView(
    val emailEnabled: Boolean,
    val dueSoonEnabled: Boolean,
    val overdueEnabled: Boolean,
    val holdReadyEnabled: Boolean,
    val accountStatusEnabled: Boolean,
)

data class VersionedNotificationPreference(
    val preference: NotificationPreferenceView,
    val entityTag: String,
)
