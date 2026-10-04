package com.mundiapolis.library.bff.membership

import java.time.Instant
import java.util.UUID

data class MemberProfileView(
    val memberId: UUID,
    val email: String,
    val fullName: String,
    val universityId: Int,
    val status: AccountStatusView,
    val role: MembershipRoleView,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class AccountStatusView {
    PENDING,
    APPROVED,
    REJECTED,
}

enum class MembershipRoleView {
    USER,
    ADMIN,
    SUPER_ADMIN,
}
