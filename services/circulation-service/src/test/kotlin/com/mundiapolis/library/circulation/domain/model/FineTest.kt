package com.mundiapolis.library.circulation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class FineTest {
    @Test
    fun `money arithmetic settles exactly without floating point`() {
        val assessed = fine(amountMinor = 501)

        val partiallyPaid = assessed.recordPayment(500)
        assertThat(partiallyPaid.balanceMinor).isOne()
        assertThat(partiallyPaid.status).isEqualTo(FineStatus.OPEN)
        assertThat(partiallyPaid.version).isOne()

        val settled = partiallyPaid.recordPayment(1)
        assertThat(settled.balanceMinor).isZero()
        assertThat(settled.status).isEqualTo(FineStatus.SETTLED)
        assertThat(settled.version).isEqualTo(2)
    }

    @Test
    fun `money bounds reject overflow overpayment and currencies without minor units`() {
        val assessed = fine(amountMinor = Fine.MAX_AMOUNT_MINOR)

        assertThatIllegalArgumentException()
            .isThrownBy { assessed.adjust(1) }
        assertThatIllegalArgumentException()
            .isThrownBy { assessed.recordPayment(Fine.MAX_AMOUNT_MINOR + 1) }
        assertThatIllegalArgumentException()
            .isThrownBy { fine(amountMinor = 100, currency = "XXX") }
    }

    private fun fine(
        amountMinor: Long,
        currency: String = "MAD",
    ): Fine = Fine.assess(
        id = FineId(UUID.randomUUID()),
        loanId = LoanId(UUID.randomUUID()),
        memberId = MemberId(UUID.randomUUID()),
        currency = currency,
        amountMinor = amountMinor,
        assessedAt = Instant.parse("2026-07-26T00:00:00Z"),
    )
}
