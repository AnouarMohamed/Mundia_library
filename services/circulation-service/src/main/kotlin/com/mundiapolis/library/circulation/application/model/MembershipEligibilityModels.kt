package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.EligibilityReasonCode
import com.mundiapolis.library.circulation.domain.model.MemberEligibility
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

data class MembershipEligibilityEvent(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val memberId: MemberId,
    val aggregateVersion: Long,
    val status: MemberEligibilityStatus,
    val reasonCode: EligibilityReasonCode?,
    val occurredAt: Instant,
) {
    init {
        require(eventType == EVENT_TYPE) { "Unsupported membership eligibility event type" }
        require(eventVersion == EVENT_VERSION) { "Unsupported membership eligibility event version" }
        require(aggregateVersion >= 0) { "Membership aggregate version cannot be negative" }
        MemberEligibility(memberId, status, reasonCode, aggregateVersion, occurredAt)
    }

    fun payloadFingerprint(): String {
        val canonical = listOf(
            "membership-eligibility-event-v1",
            eventId.toString(),
            eventType,
            eventVersion.toString(),
            memberId.value.toString(),
            aggregateVersion.toString(),
            status.name,
            reasonCode?.value ?: NULL_MARKER,
            occurredAt.toString(),
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val EVENT_TYPE = "membership.member.eligibility-changed"
        const val EVENT_VERSION = 1
        private const val NULL_MARKER = "<null>"
    }
}

enum class EligibilityEventDisposition {
    APPLIED,
    STALE,
}

data class EligibilityEventExecution(
    val disposition: EligibilityEventDisposition,
    val replayed: Boolean,
    val eligibility: MemberEligibility,
)

data class ProcessedConsumerEvent(
    val consumerName: String,
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val payloadFingerprint: String,
    val disposition: EligibilityEventDisposition,
    val receivedAt: Instant,
    val processedAt: Instant,
)

data class MemberEligibilityView(
    val memberId: MemberId,
    val status: MemberEligibilityStatus,
    val reasonCode: String?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
) {
    companion object {
        fun from(eligibility: MemberEligibility): MemberEligibilityView = MemberEligibilityView(
            memberId = eligibility.memberId,
            status = eligibility.status,
            reasonCode = eligibility.reasonCode?.value,
            sourceVersion = eligibility.sourceVersion,
            sourceOccurredAt = eligibility.sourceOccurredAt,
        )
    }
}

data class CirculationPolicyView(
    val revision: String,
    val defaultLoanPeriod: Duration,
    val renewalPeriod: Duration,
    val maximumRenewals: Int,
    val fineCurrency: String,
)

sealed class MembershipEligibilityException(message: String) : RuntimeException(message)

class MembershipEventGapException(expected: Long, actual: Long) :
    MembershipEligibilityException(
        "Membership eligibility event gap: expected aggregate version $expected but received $actual",
    )

class MembershipEventConflictException :
    MembershipEligibilityException(
        "Membership eligibility event conflicts with an already processed event or version",
    )

class MembershipEventClockSkewException(maximumFutureSkew: Duration) :
    MembershipEligibilityException(
        "Membership eligibility event exceeds the allowed future clock skew of $maximumFutureSkew",
    )

class MemberEligibilityNotFoundException(memberId: MemberId) :
    CirculationCommandException("Eligibility for member ${memberId.value} was not found")

class MemberEligibilityUnavailableException(memberId: MemberId) :
    CirculationCommandException(
        "Eligibility for member ${memberId.value} is unavailable; circulation fails closed",
    )

class MemberNotEligibleException(memberId: MemberId, status: MemberEligibilityStatus) :
    CirculationCommandException(
        "Member ${memberId.value} is not eligible for circulation while status is $status",
    )
