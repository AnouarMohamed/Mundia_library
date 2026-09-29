package com.mundiapolis.library.notification.service.impl

import com.mundiapolis.library.notification.adapter.outbound.persistence.JooqNotificationRepository
import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationReadStatus
import com.mundiapolis.library.notification.service.NotificationNotFoundException
import com.mundiapolis.library.notification.service.NotificationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class NotificationServiceImpl(
    private val repository: JooqNotificationRepository,
    private val clock: Clock,
) : NotificationService {
    override fun list(
        memberId: UUID,
        status: NotificationReadStatus,
        limit: Int,
        cursor: String?,
    ): NotificationPage = repository.findPage(memberId, status, limit, cursor)

    @Transactional
    override fun markRead(memberId: UUID, notificationId: UUID): NotificationItem =
        repository.markRead(memberId, notificationId, clock.instant())
            ?: throw NotificationNotFoundException()
}
