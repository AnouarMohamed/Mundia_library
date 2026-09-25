package com.mundiapolis.library.catalog.dto

import java.time.Instant
import java.util.UUID

enum class ProjectedCopyStatus {
    AVAILABLE,
    ON_LOAN,
    RESERVED,
    LOST,
    DAMAGED,
    WITHDRAWN,
}

data class CirculationCopyEvent(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val copyId: UUID,
    val editionId: UUID,
    val aggregateVersion: Long,
    val status: ProjectedCopyStatus,
    val occurredAt: Instant,
    val payloadSha256: String,
)

enum class ProjectedLoanStatus {
    REQUESTED,
    ACTIVE,
    RETURNED,
    REJECTED,
    CANCELLED,
}

data class CirculationLoanEvent(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val loanId: UUID,
    val memberId: UUID,
    val editionId: UUID,
    val copyId: UUID?,
    val aggregateVersion: Long,
    val status: ProjectedLoanStatus,
    val returnedAt: Instant?,
    val occurredAt: Instant,
    val payloadSha256: String,
)

sealed interface DecodedCirculationRecord {
    data class Copy(val event: CirculationCopyEvent) : DecodedCirculationRecord

    data class Loan(val event: CirculationLoanEvent) : DecodedCirculationRecord

    data object Ignored : DecodedCirculationRecord
}

enum class ConsumerEventDisposition {
    APPLIED,
    STALE,
}

data class AvailabilityEventExecution(
    val disposition: ConsumerEventDisposition,
    val replayed: Boolean,
)

class CirculationEventGapException(expected: Long, actual: Long) :
    RuntimeException("Expected copy event version $expected but received $actual")

class CirculationEventConflictException : RuntimeException("Circulation event conflicts with projected state")

class CirculationEventClockSkewException : RuntimeException("Circulation event occurrence time is too far in the future")
