package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.CirculationClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class CirculationClientPropertiesTest {
    @Test
    fun `protected deployments require https`() {
        assertThat(properties().isSafeFor("production")).isTrue()
        assertThat(
            properties().copy(baseUrl = URI("http://circulation.internal"))
                .isSafeFor("production"),
        ).isFalse()
        assertThat(
            properties().copy(baseUrl = URI("http://localhost:8083"))
                .isSafeFor("local"),
        ).isTrue()
    }

    @Test
    fun `audience transport and delegated token lifetime remain bounded`() {
        assertThat(properties().copy(audience = "circulation api").isValid).isFalse()
        assertThat(properties().copy(readTimeout = Duration.ofSeconds(11)).isValid).isFalse()
        assertThat(properties().copy(maximumResponseBytes = 3_000).isValid).isFalse()
        assertThat(
            properties().copy(maximumDelegatedTokenLifetime = Duration.ofMinutes(11)).isValid,
        ).isFalse()
    }

    private fun properties() = CirculationClientProperties(
        baseUrl = URI("https://circulation.internal"),
        audience = "circulation-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(5),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )
}
