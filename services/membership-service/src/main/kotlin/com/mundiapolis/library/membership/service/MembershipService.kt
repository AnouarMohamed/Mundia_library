package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile

interface MembershipService {
    fun getMemberProfile(memberId: String): MemberProfile?
    fun checkEligibility(memberId: String): MemberEligibility
    fun getIdentityEvidenceRef(memberId: String): IdentityEvidenceRef?
}
