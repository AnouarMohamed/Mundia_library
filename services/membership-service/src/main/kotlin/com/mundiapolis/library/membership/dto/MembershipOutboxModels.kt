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

class MembershipOutboxContractException(message: String) : RuntimeException(message)

class MembershipOutboxPayloadTooLargeException :
    RuntimeException("Membership event exceeds its size limit")
