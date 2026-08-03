package com.mundiapolis.library.circulation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MemberEligibilityTest {
    private val memberId = MemberId(UUID.randomUUID())
    private val occurredAt = Instant.parse("2026-08-03T12:00:00Z")

    @Test
    fun `eligible state has no reason while blocked states require one`() {
        val eligible = MemberEligibility(
            memberId = memberId,
            status = MemberEligibilityStatus.ELIGIBLE,
            reasonCode = null,
            sourceVersion = 0,
            sourceOccurredAt = occurredAt,
        )
        val suspended = MemberEligibility(
            memberId = memberId,
            status = MemberEligibilityStatus.SUSPENDED,
            reasonCode = EligibilityReasonCode.parse("ACCOUNT_SUSPENDED"),
            sourceVersion = 1,
            sourceOccurredAt = occurredAt,
        )

        assertThat(eligible.isEligible).isTrue()
        assertThat(suspended.isEligible).isFalse()
        assertThatIllegalArgumentException().isThrownBy {
            eligible.copy(reasonCode = EligibilityReasonCode.parse("UNEXPECTED_REASON"))
        }
        assertThatIllegalArgumentException().isThrownBy {
            suspended.copy(reasonCode = null)
        }
    }

    @Test
    fun `reason code and source version reject malformed values`() {
        assertThatIllegalArgumentException().isThrownBy {
            EligibilityReasonCode.parse("lowercase")
        }
        assertThatIllegalArgumentException().isThrownBy {
            MemberEligibility(
                memberId = memberId,
                status = MemberEligibilityStatus.ELIGIBLE,
                reasonCode = null,
                sourceVersion = -1,
                sourceOccurredAt = occurredAt,
            )
        }
    }
}
