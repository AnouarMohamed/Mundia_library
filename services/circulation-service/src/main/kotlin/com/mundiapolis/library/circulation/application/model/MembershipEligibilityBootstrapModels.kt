package com.mundiapolis.library.circulation.application.model

import com.mundiapolis.library.circulation.domain.model.EligibilityReasonCode
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import java.time.Instant
import java.util.UUID

data class MembershipEligibilityBootstrapItem(
    val memberId: MemberId,
    val status: MemberEligibilityStatus,
    val reasonCode: EligibilityReasonCode?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
    val contentSha256: String,
)

data class MembershipEligibilityBootstrapCommand(
    val bootstrapId: UUID,
    val sourceRevision: String,
    val items: List<MembershipEligibilityBootstrapItem>,
    val actorFingerprint: String,
)

data class MembershipEligibilityBootstrapResult(
    val bootstrapId: UUID,
    val sourceRevision: String,
    val manifestSha256: String,
    val memberCount: Int,
    val completedAt: Instant,
    val replayed: Boolean,
)

class InvalidMembershipEligibilityBootstrapException(message: String) : RuntimeException(message)

class MembershipEligibilityBootstrapConflictException(message: String) : RuntimeException(message)

class MembershipEligibilityBootstrapNotFoundException :
    RuntimeException("Membership eligibility bootstrap does not exist")
