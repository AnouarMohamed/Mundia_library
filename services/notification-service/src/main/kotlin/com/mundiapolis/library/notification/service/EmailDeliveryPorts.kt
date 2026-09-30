package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.dto.ClaimedEmailDelivery
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.mundiapolis.library.notification.dto.EmailDeliveryStatistics
import com.mundiapolis.library.notification.dto.EmailProviderReceipt
import com.mundiapolis.library.notification.dto.EmailRecipient
import java.time.Instant
import java.util.UUID

interface EmailDeliveryStore {
    fun claimBatch(
        owner: String,
        leaseToken: UUID,
        now: Instant,
        leaseExpiresAt: Instant,
        batchSize: Int,
        maximumAttempts: Int,
    ): List<ClaimedEmailDelivery>

    fun markDelivered(
        owner: String,
        delivery: ClaimedEmailDelivery,
        receipt: EmailProviderReceipt,
        deliveredAt: Instant,
    ): Boolean

    fun recordFailure(
        owner: String,
        delivery: ClaimedEmailDelivery,
        failureCode: EmailDeliveryFailureCode,
        failedAt: Instant,
        nextAttemptAt: Instant?,
        deadLetter: Boolean,
    ): Boolean

    fun statistics(now: Instant): EmailDeliveryStatistics
}

fun interface NotificationRecipientResolver {
    fun resolve(memberId: UUID): EmailRecipient
}

fun interface EmailProvider {
    fun send(
        recipient: EmailRecipient,
        subject: String,
        body: String,
        deliveryId: UUID,
    ): EmailProviderReceipt
}
