package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.CirculationPolicy
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class UpdateCirculationPolicyValues(
    val defaultLoanPeriod: Duration,
    val renewalPeriod: Duration,
    val maximumRenewals: Int,
    val fineCurrency: String,
    val reservationHoldPeriod: Duration,
    val maximumActiveReservations: Int,
)

data class PolicyCommandExecution(
    val result: CirculationPolicyView,
    val replayed: Boolean,
)

data class StoredPolicyIdempotencyResult(
    val requestFingerprint: String,
    val revisionId: UUID?,
)

data class PolicyOutboxEvent(
    val id: UUID,
    val policy: CirculationPolicy,
    val occurredAt: Instant,
)

sealed class PolicyCommandException(message: String) : RuntimeException(message)

class InvalidCirculationPolicyException(message: String) : PolicyCommandException(message)

class PolicyRevisionConflictException :
    PolicyCommandException("The circulation policy changed; reload it before updating")

class PolicyRevisionNotFoundException :
    PolicyCommandException("The stored policy revision is unavailable")
