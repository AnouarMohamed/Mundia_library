package com.mundiapolis.library.circulation.config

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class CirculationIdempotencyPropertiesTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `requires retention between one second and 365 days`() {
        val belowMinimum = CirculationIdempotencyProperties(Duration.ofMillis(999))
        val minimum = CirculationIdempotencyProperties(Duration.ofSeconds(1))
        val maximum = CirculationIdempotencyProperties(Duration.ofDays(365))
        val aboveMaximum = CirculationIdempotencyProperties(Duration.ofDays(365).plusMillis(1))

        assertThat(validator.validate(belowMinimum)).isNotEmpty()
        assertThat(validator.validate(minimum)).isEmpty()
        assertThat(validator.validate(maximum)).isEmpty()
        assertThat(validator.validate(aboveMaximum)).isNotEmpty()
    }
}
