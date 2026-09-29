package com.mundiapolis.library.notification.service.impl

import com.mundiapolis.library.notification.adapter.outbound.persistence.JooqNotificationRepository
import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationPreference
import com.mundiapolis.library.notification.dto.NotificationReadStatus
import com.mundiapolis.library.notification.dto.UpdateNotificationPreferenceRequest
import com.mundiapolis.library.notification.service.NotificationNotFoundException
import com.mundiapolis.library.notification.service.NotificationPreferenceVersionConflictException
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

    override fun getPreference(memberId: UUID): NotificationPreference =
        repository.findPreference(memberId) ?: defaultPreference(memberId)

    @Transactional
    override fun updatePreference(
        memberId: UUID,
        expectedVersion: Long,
        request: UpdateNotificationPreferenceRequest,
    ): NotificationPreference {
        require(expectedVersion >= 0) { "Preference version must be nonnegative" }
        val current = repository.findPreference(memberId)
        if (current == null) {
            if (expectedVersion != 0L) throw NotificationPreferenceVersionConflictException()
            repository.insertPreference(memberId, request, clock.instant())?.let { return it }
            return resolveConcurrentUpdate(memberId, expectedVersion, request)
        }
        if (current.matches(request)) {
            if (
                expectedVersion == current.version ||
                (current.version > 0 && expectedVersion == current.version - 1)
            ) {
                return current
            }
            throw NotificationPreferenceVersionConflictException()
        }
        if (current.version != expectedVersion) throw NotificationPreferenceVersionConflictException()
        return repository.updatePreference(memberId, expectedVersion, request, clock.instant())
            ?: resolveConcurrentUpdate(memberId, expectedVersion, request)
    }

    private fun resolveConcurrentUpdate(
        memberId: UUID,
        expectedVersion: Long,
        request: UpdateNotificationPreferenceRequest,
    ): NotificationPreference {
        val concurrent = repository.findPreference(memberId)
            ?: throw NotificationPreferenceVersionConflictException()
        if (
            concurrent.matches(request) &&
            expectedVersion < Long.MAX_VALUE &&
            concurrent.version == expectedVersion + 1
        ) {
            return concurrent
        }
        throw NotificationPreferenceVersionConflictException()
    }

    private fun defaultPreference(memberId: UUID): NotificationPreference = NotificationPreference(
        memberId = memberId.toString(),
        emailEnabled = true,
        dueSoonEnabled = true,
        overdueEnabled = true,
        holdReadyEnabled = true,
        accountStatusEnabled = true,
        version = 0,
        updatedAt = null,
    )

    private fun NotificationPreference.matches(request: UpdateNotificationPreferenceRequest): Boolean =
        emailEnabled == request.emailEnabled &&
            dueSoonEnabled == request.dueSoonEnabled &&
            overdueEnabled == request.overdueEnabled &&
            holdReadyEnabled == request.holdReadyEnabled &&
            accountStatusEnabled == request.accountStatusEnabled
}
