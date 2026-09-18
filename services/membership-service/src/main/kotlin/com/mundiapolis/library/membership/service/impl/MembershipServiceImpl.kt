package com.mundiapolis.library.membership.service.impl

import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import com.mundiapolis.library.membership.dto.MembershipRole
import com.mundiapolis.library.membership.service.MembershipService
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class MembershipServiceImpl : MembershipService {

    // In-memory storage for demonstration - to be replaced with actual database implementation
    private val memberProfiles = ConcurrentHashMap<String, MemberProfile>()
    private val memberEligibilities = ConcurrentHashMap<String, MemberEligibility>()
    private val identityEvidenceRefs = ConcurrentHashMap<String, IdentityEvidenceRef?>()

    init {
        // Initialize with some test data
        val testMemberId = "test-member-001"
        val testProfile = MemberProfile(
            memberId = testMemberId,
            email = "test@user.com",
            fullName = "Test User",
            universityId = 12345,
            status = AccountStatus.APPROVED,
            role = MembershipRole.USER,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        memberProfiles[testMemberId] = testProfile

        val testEligibility = MemberEligibility(
            memberId = testMemberId,
            eligible = true,
            status = AccountStatus.APPROVED,
            maxActiveLoans = 5,
            currentActiveLoans = 0,
            hasUnpaidOverdueFines = false,
            evaluatedAt = Instant.now()
        )
        memberEligibilities[testMemberId] = testEligibility
    }

    override suspend fun getMemberProfile(memberId: String): MemberProfile? {
        return memberProfiles[memberId]
    }

    override suspend fun checkEligibility(memberId: String): MemberEligibility {
        return memberEligibilities[memberId] ?: MemberEligibility(
            memberId = memberId,
            eligible = false,
            status = AccountStatus.REJECTED,
            maxActiveLoans = 0,
            currentActiveLoans = 0,
            hasUnpaidOverdueFines = false,
            reason = "Member not found",
            evaluatedAt = Instant.now()
        )
    }

    override suspend fun getIdentityEvidenceRef(memberId: String): IdentityEvidenceRef? {
        return identityEvidenceRefs[memberId]
    }
}
