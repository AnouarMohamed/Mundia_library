package com.mundiapolis.library.circulation.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class LoanReminderPropertiesTest {
    @Test
    fun `scheduler timing and batch bounds are fail safe`() {
        assertThat(properties().isPollIntervalValid).isTrue()
        assertThat(properties().isDueSoonLeadTimeValid).isTrue()
        assertThat(properties().isBatchSizeValid).isTrue()
        assertThat(properties().copy(pollInterval = Duration.ofSeconds(9)).isPollIntervalValid)
            .isFalse()
        assertThat(properties().copy(dueSoonLeadTime = Duration.ofDays(31)).isDueSoonLeadTimeValid)
            .isFalse()
        assertThat(properties().copy(batchSize = 0).isBatchSizeValid).isFalse()
    }

    private fun properties() = LoanReminderProperties(
        enabled = true,
        pollInterval = Duration.ofMinutes(1),
        dueSoonLeadTime = Duration.ofDays(3),
        batchSize = 100,
    )
}
