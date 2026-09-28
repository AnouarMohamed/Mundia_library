package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.BffProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class BffPropertiesTest {
    @Test
    fun `protected deployments require HTTPS and secure cookies`() {
        assertThat(properties().isSafeConfiguration).isTrue()
        assertThat(
            properties().copy(publicBaseUrl = URI("http://library.example.test"))
                .isSafeConfiguration,
        ).isFalse()
        assertThat(properties().copy(secureCookies = false).isSafeConfiguration).isFalse()
    }

    @Test
    fun `sessions cannot exceed the absolute eight hour limit`() {
        assertThat(
            properties().copy(sessionMaximumAge = Duration.ofHours(9)).isSafeConfiguration,
        ).isFalse()
    }

    @Test
    fun `public base URL is exact and has no trailing path`() {
        assertThat(
            properties().copy(publicBaseUrl = URI("https://library.example.test/"))
                .isSafeConfiguration,
        ).isFalse()
    }

    private fun properties() = BffProperties(
        deploymentTier = "production",
        publicBaseUrl = URI("https://library.example.test"),
        secureCookies = true,
        sessionMaximumAge = Duration.ofHours(8),
    )
}
