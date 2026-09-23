package com.mundiapolis.library.membership.service.impl

import com.mundiapolis.library.membership.adapter.outbound.persistence.JooqMembershipRepository
import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import com.mundiapolis.library.membership.service.MembershipService
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class MembershipServiceImpl(
    private val repository: JooqMembershipRepository,
    private val clock: Clock,
) : MembershipService {
    override fun getMemberProfile(memberId: String): MemberProfile? =
        repository.findProfile(memberId.toMemberId())

    override fun checkEligibility(memberId: String): MemberEligibility =
        repository.findEligibility(memberId.toMemberId()) ?: MemberEligibility(
            memberId = memberId,
            eligible = false,
            status = AccountStatus.REJECTED,
            maxActiveLoans = 0,
            currentActiveLoans = 0,
            hasUnpaidOverdueFines = false,
            reason = "Member not found",
            evaluatedAt = clock.instant(),
        )

    override fun getIdentityEvidenceRef(memberId: String): IdentityEvidenceRef? =
        repository.findIdentityEvidence(memberId.toMemberId())

    private fun String.toMemberId(): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse { throw IllegalArgumentException("memberId must be a canonical UUID") }
}
