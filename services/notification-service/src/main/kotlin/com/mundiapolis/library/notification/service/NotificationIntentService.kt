package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.adapter.outbound.persistence.JooqNotificationRepository
import com.mundiapolis.library.notification.dto.NotificationIntentClockSkewException
import com.mundiapolis.library.notification.dto.NotificationChannel
import com.mundiapolis.library.notification.dto.NotificationIntentCommand
import com.mundiapolis.library.notification.dto.NotificationIntentConflictException
import com.mundiapolis.library.notification.dto.NotificationIntentExecution
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class NotificationIntentService(
    private val repository: JooqNotificationRepository,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) : NotificationIntentHandler {
    override fun apply(intent: NotificationIntentCommand): NotificationIntentExecution = requireNotNull(
        transactionTemplate.execute {
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val occurredAt = intent.occurredAt.truncatedTo(ChronoUnit.MICROS)
            if (
                occurredAt < EARLIEST_SUPPORTED_EVENT_TIME ||
                occurredAt > now.plus(MAXIMUM_FUTURE_CLOCK_SKEW)
            ) {
                throw NotificationIntentClockSkewException()
            }
            val normalized = intent.copy(occurredAt = occurredAt)
            repository.lockMemberNotificationState(normalized.memberId)
            val notificationId = UUID.randomUUID()
            val createdAt = laterOf(now, occurredAt)
            if (!repository.insertIntentInbox(normalized, notificationId, createdAt)) {
                val receipt = repository.findIntentReceipt(normalized.eventId)
                    ?: throw NotificationIntentConflictException()
                if (receipt.payloadSha256 != normalized.payloadSha256) {
                    throw NotificationIntentConflictException()
                }
                return@execute NotificationIntentExecution(receipt.notificationId, replayed = true)
            }
            val preference = repository.findPreference(normalized.memberId)
            val emailSuppressed = repository.isEmailSuppressed(normalized.memberId)
            normalized.channels.sortedBy { it.name }.forEach {
                val suppressed = it == NotificationChannel.EMAIL &&
                    (emailSuppressed || preference?.allowsEmail(normalized.category) == false)
                repository.insertDelivery(notificationId, it, createdAt, suppressed)
            }
            repository.insertIntentReceipt(normalized, notificationId, now)
            NotificationIntentExecution(notificationId, replayed = false)
        },
    )

    private fun laterOf(first: Instant, second: Instant): Instant = if (first >= second) first else second

    private companion object {
        val MAXIMUM_FUTURE_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        val EARLIEST_SUPPORTED_EVENT_TIME: Instant = Instant.parse("2000-01-01T00:00:00Z")
    }
}

fun interface NotificationIntentHandler {
    fun apply(intent: NotificationIntentCommand): NotificationIntentExecution
}
