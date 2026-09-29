package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationReadStatus
import java.util.UUID

interface NotificationService {
    fun list(memberId: UUID, status: NotificationReadStatus, limit: Int, cursor: String?): NotificationPage
    fun markRead(memberId: UUID, notificationId: UUID): NotificationItem
}

class NotificationNotFoundException : RuntimeException("Notification not found")
