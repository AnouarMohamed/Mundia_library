package com.mundiapolis.library.circulation.application.model

import java.time.Instant
import java.util.UUID

data class ClaimedOutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val traceId: String?,
    val payloadJson: String,
    val createdAt: Instant,
    val deliveryAttempt: Int,
    val leaseToken: UUID,
)

data class EncodedOutboxEvent(
    val eventId: UUID,
    val key: String,
    val eventType: String,
    val eventVersion: Int,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val schemaSubject: String,
    val schemaVersion: Int,
    val payload: ByteArray,
)

data class BrokerPublishAcknowledgement(
    val topic: String,
    val partition: Int,
    val offset: Long,
)

data class OutboxDeliveryStatistics(
    val pending: Long,
    val leased: Long,
    val blocked: Long,
    val oldestPendingCreatedAt: Instant?,
)

enum class OutboxFailureCode {
    CONTRACT_INVALID,
    PAYLOAD_TOO_LARGE,
    BROKER_AUTHENTICATION,
    BROKER_AUTHORIZATION,
    BROKER_TIMEOUT,
    BROKER_UNAVAILABLE,
    BROKER_REJECTED,
}

enum class OutboxFailureDisposition {
    RETRY_SCHEDULED,
    BLOCKED,
    CLAIM_LOST,
}

data class OutboxDeliveryCycle(
    val claimed: Int,
    val published: Int,
    val retryScheduled: Int,
    val blocked: Int,
    val claimLost: Int,
)

class BrokerPublishException(
    val failureCode: OutboxFailureCode,
) : RuntimeException("Broker publish failed")
