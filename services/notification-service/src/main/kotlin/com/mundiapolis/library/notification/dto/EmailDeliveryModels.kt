package com.mundiapolis.library.notification.dto

import java.time.Instant
import java.util.UUID

data class ClaimedEmailDelivery(
    val deliveryId: UUID,
    val notificationId: UUID,
    val memberId: UUID,
    val subject: String,
    val body: String,
    val attempt: Int,
    val leaseToken: UUID,
)

data class EmailRecipient(val address: String)

data class EmailProviderReceipt(
    val provider: String,
    val messageReference: String,
)

data class EmailDeliveryCycle(
    val claimed: Int,
    val delivered: Int,
    val suppressed: Int,
    val retryScheduled: Int,
    val deadLettered: Int,
    val claimLost: Int,
)

data class EmailDeliveryStatistics(
    val pending: Long,
    val delivering: Long,
    val retrying: Long,
    val deadLettered: Long,
    val expiredLeases: Long,
    val oldestPendingAt: Instant?,
)

enum class EmailDeliveryFailureCode(val retryable: Boolean) {
    LEASE_EXPIRED(true),
    RECIPIENT_NOT_FOUND(false),
    RECIPIENT_UNAVAILABLE(true),
    PROVIDER_RATE_LIMITED(true),
    PROVIDER_UNAVAILABLE(true),
    PROVIDER_REJECTED(false),
    INTERNAL(true),
}

class EmailDeliveryException(
    val failureCode: EmailDeliveryFailureCode,
) : RuntimeException("Email delivery failed")
