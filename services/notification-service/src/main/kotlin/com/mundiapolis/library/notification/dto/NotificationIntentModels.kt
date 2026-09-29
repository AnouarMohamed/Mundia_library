package com.mundiapolis.library.notification.dto

import java.time.Instant
import java.util.UUID

enum class NotificationChannel { IN_APP, EMAIL }

data class NotificationIntentCommand(
    val eventId: UUID,
    val memberId: UUID,
    val sourceType: String,
    val category: NotificationCategory,
    val subject: String,
    val body: String,
    val occurredAt: Instant,
    val channels: Set<NotificationChannel>,
    val payloadSha256: String,
    val topic: String,
    val partition: Int,
    val offset: Long,
)

data class NotificationIntentExecution(
    val notificationId: UUID,
    val replayed: Boolean,
)

class NotificationIntentConflictException : RuntimeException()
class NotificationIntentClockSkewException : RuntimeException()
