package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationPreference
import com.mundiapolis.library.notification.dto.NotificationReadStatus
import com.mundiapolis.library.notification.dto.UpdateNotificationPreferenceRequest
import java.util.UUID

interface NotificationService {
    fun list(memberId: UUID, status: NotificationReadStatus, limit: Int, cursor: String?): NotificationPage
    fun markRead(memberId: UUID, notificationId: UUID): NotificationItem
    fun getPreference(memberId: UUID): NotificationPreference
    fun updatePreference(
        memberId: UUID,
        expectedVersion: Long,
        request: UpdateNotificationPreferenceRequest,
    ): NotificationPreference
}

class NotificationNotFoundException : RuntimeException("Notification not found")
class NotificationPreferencePreconditionRequiredException :
    RuntimeException("A strong If-Match preference version is required")

class NotificationPreferenceVersionConflictException :
    RuntimeException("Notification preference version does not match")
