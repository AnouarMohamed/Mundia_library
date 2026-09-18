package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import com.mundiapolis.library.membership.dto.MembershipRole
import kotlinx.coroutines.flow.Flow

interface MembershipService {
    suspend fun getMemberProfile(memberId: String): MemberProfile?
    suspend fun checkEligibility(memberId: String): MemberEligibility
    suspend fun getIdentityEvidenceRef(memberId: String): IdentityEvidenceRef?
}
