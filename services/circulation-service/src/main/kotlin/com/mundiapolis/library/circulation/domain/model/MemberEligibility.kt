package com.mundiapolis.library.circulation.domain.model

import java.time.Instant

enum class MemberEligibilityStatus {
    ELIGIBLE,
    INELIGIBLE,
    SUSPENDED,
}

@JvmInline
value class EligibilityReasonCode private constructor(val value: String) {
    companion object {
        private val ALLOWED = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun parse(raw: String): EligibilityReasonCode {
            require(ALLOWED.matches(raw)) {
                "Eligibility reason code must contain 1 to 64 uppercase letters, digits, or underscores"
            }
            return EligibilityReasonCode(raw)
        }
    }
}

data class MemberEligibility(
    val memberId: MemberId,
    val status: MemberEligibilityStatus,
    val reasonCode: EligibilityReasonCode?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
) {
    init {
        require(sourceVersion >= 0) { "Eligibility source version cannot be negative" }
        require(
            (status == MemberEligibilityStatus.ELIGIBLE && reasonCode == null) ||
                (status != MemberEligibilityStatus.ELIGIBLE && reasonCode != null),
        ) { "Only an ineligible or suspended member may have an eligibility reason" }
    }

    val isEligible: Boolean
        get() = status == MemberEligibilityStatus.ELIGIBLE
}
