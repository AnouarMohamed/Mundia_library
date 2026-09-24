package com.mundiapolis.library.catalog.dto

import java.time.Instant
import java.util.UUID

data class ClaimedCatalogOutboxEvent(
    val eventId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val payloadJson: String,
    val deliveryAttempt: Int,
    val leaseToken: UUID,
)

data class EncodedCatalogEvent(
    val eventId: UUID,
    val key: String,
    val eventType: String,
    val eventVersion: Int,
    val schemaSubject: String,
    val schemaVersion: Int,
    val payload: ByteArray,
)

data class BrokerAcknowledgement(
    val topic: String,
    val partition: Int,
    val offset: Long,
)

data class CatalogOutboxStatistics(
    val pending: Long,
    val leased: Long,
    val blocked: Long,
    val oldestPendingCreatedAt: Instant?,
)

data class CatalogOutboxDeliveryCycle(
    val claimed: Int,
    val published: Int,
    val retryScheduled: Int,
    val blocked: Int,
    val claimLost: Int,
)

enum class CatalogOutboxFailureCode {
    CONTRACT_INVALID,
    PAYLOAD_TOO_LARGE,
    BROKER_AUTHENTICATION,
    BROKER_AUTHORIZATION,
    BROKER_TIMEOUT,
    BROKER_UNAVAILABLE,
    BROKER_REJECTED,
}

enum class CatalogOutboxFailureDisposition {
    RETRY_SCHEDULED,
    BLOCKED,
    CLAIM_LOST,
}

class CatalogBrokerPublishException(
    val failureCode: CatalogOutboxFailureCode,
) : RuntimeException("Catalog broker publish failed")

class CatalogOutboxContractException(message: String) : RuntimeException(message)

class CatalogOutboxPayloadTooLargeException : RuntimeException("Catalog event exceeds its size limit")
