package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.MembershipClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class MembershipClientPropertiesTest {
    @Test
    fun `protected deployments require HTTPS`() {
        assertThat(properties().isSafeFor("production")).isTrue()
        assertThat(
            properties().copy(baseUrl = URI("http://membership.internal"))
                .isSafeFor("production"),
        ).isFalse()
        assertThat(
            properties().copy(baseUrl = URI("http://localhost:8081"))
                .isSafeFor("local"),
        ).isTrue()
    }

    @Test
    fun `audience transport and delegated token lifetime remain bounded`() {
        assertThat(properties().copy(audience = "membership api").isValid).isFalse()
        assertThat(properties().copy(readTimeout = Duration.ofSeconds(11)).isValid).isFalse()
        assertThat(properties().copy(maximumResponseBytes = 3_000).isValid).isFalse()
        assertThat(
            properties().copy(maximumDelegatedTokenLifetime = Duration.ofMinutes(11)).isValid,
        ).isFalse()
    }

    private fun properties() = MembershipClientProperties(
        baseUrl = URI("https://membership.internal"),
        audience = "membership-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )
}
