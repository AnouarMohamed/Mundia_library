package com.mundiapolis.library.circulation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class LoanTest {
    private val requestedAt = Instant.parse("2026-07-26T10:00:00Z")

    @Test
    fun `approval and return preserve lifecycle invariants`() {
        val requested = requestedLoan()
        val checkedOutAt = requestedAt.plus(1, ChronoUnit.HOURS)
        val dueAt = checkedOutAt.plus(14, ChronoUnit.DAYS)

        val active = requested.approve(CopyId(UUID.randomUUID()), checkedOutAt, dueAt)
        val returned = active.returnAt(checkedOutAt.plus(3, ChronoUnit.DAYS))

        assertThat(active.status).isEqualTo(LoanStatus.ACTIVE)
        assertThat(active.version).isEqualTo(1)
        assertThat(returned.status).isEqualTo(LoanStatus.RETURNED)
        assertThat(returned.version).isEqualTo(2)
    }

    @Test
    fun `cannot approve with a due time before checkout`() {
        val checkedOutAt = requestedAt.plus(1, ChronoUnit.HOURS)

        assertThatThrownBy {
            requestedLoan().approve(
                CopyId(UUID.randomUUID()),
                checkedOutAt,
                checkedOutAt.minusSeconds(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun requestedLoan(): Loan = Loan.request(
        id = LoanId(UUID.randomUUID()),
        memberId = MemberId(UUID.randomUUID()),
        editionId = EditionId(UUID.randomUUID()),
        requestedAt = requestedAt,
    )
}
