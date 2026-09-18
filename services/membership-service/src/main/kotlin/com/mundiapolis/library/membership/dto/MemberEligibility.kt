package com.mundiapolis.library.membership.dto

import java.time.Instant

data class MemberEligibility(
    val memberId: String,
    val eligible: Boolean,
    val status: AccountStatus,
    val maxActiveLoans: Int,
    val currentActiveLoans: Int,
    val hasUnpaidOverdueFines: Boolean,
    val reason: String? = null,
    val evaluatedAt: Instant
)
