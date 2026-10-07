package com.mundiapolis.library.membership.dto

import java.time.Instant
import java.util.UUID

data class AdminMemberSummary(
    val memberId: UUID,
    val email: String,
    val fullName: String,
    val universityId: Int,
    val status: AccountStatus,
    val role: MembershipRole,
    val aggregateVersion: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminMemberPage(
    val items: List<AdminMemberSummary>,
    val nextCursor: String?,
)
