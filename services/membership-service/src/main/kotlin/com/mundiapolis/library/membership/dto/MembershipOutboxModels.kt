package com.mundiapolis.library.membership.dto

import java.time.Instant
import java.util.UUID

data class ClaimedMembershipOutboxEvent(
    val eventId: UUID,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val payloadJson: String,
    val deliveryAttempt: Int,
    val leaseToken: UUID,
)

data class EncodedMembershipEvent(
    val eventId: UUID,
    val key: String,
    val eventType: String,
    val eventVersion: Int,
    val schemaSubject: String,
    val schemaVersion: Int,
    val payload: ByteArray,
)

data class MembershipBrokerAcknowledgement(
    val topic: String,
    val partition: Int,
    val offset: Long,
)

data class MembershipOutboxStatistics(
    val pending: Long,
    val leased: Long,
    val blocked: Long,
    val oldestPendingCreatedAt: Instant?,
)

data class MembershipOutboxDeliveryCycle(
    val claimed: Int,
    val published: Int,
    val retryScheduled: Int,
    val blocked: Int,
    val claimLost: Int,
)

enum class MembershipOutboxFailureCode {
    CONTRACT_INVALID,
    PAYLOAD_TOO_LARGE,
    BROKER_AUTHENTICATION,
    BROKER_AUTHORIZATION,
    BROKER_TIMEOUT,
    BROKER_UNAVAILABLE,
    BROKER_REJECTED,
}

enum class MembershipOutboxFailureDisposition {
    RETRY_SCHEDULED,
    BLOCKED,
    CLAIM_LOST,
}

class MembershipBrokerPublishException(
    val failureCode: MembershipOutboxFailureCode,
) : RuntimeException("Membership broker publish failed")

class MembershipOutboxContractException(message: String) : RuntimeException(message)

class MembershipOutboxPayloadTooLargeException :
    RuntimeException("Membership event exceeds its size limit")
