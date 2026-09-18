package com.mundiapolis.library.membership.dto

import java.time.Instant

data class MemberProfile(
    val memberId: String,
    val email: String,
    val fullName: String,
    val universityId: Int,
    val status: AccountStatus,
    val role: MembershipRole,
    val createdAt: Instant,
    val updatedAt: Instant
)
