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

data class AdminMemberSummaryView(
    val memberId: UUID,
    val email: String,
    val fullName: String,
    val universityId: Int,
    val status: AccountStatusView,
    val role: MembershipRoleView,
    val aggregateVersion: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminMemberPageView(
    val items: List<AdminMemberSummaryView>,
    val nextCursor: String?,
)

data class ChangeMemberStatusView(
    val status: AccountStatusView,
    val reason: String,
)

data class MembershipCommandView(
    val memberId: UUID,
    val aggregateVersion: Long,
    val status: AccountStatusView,
    val occurredAt: Instant,
    val replayed: Boolean,
)

data class MembershipMutationResult(
    val command: MembershipCommandView,
    val idempotencyReplayed: Boolean,
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
