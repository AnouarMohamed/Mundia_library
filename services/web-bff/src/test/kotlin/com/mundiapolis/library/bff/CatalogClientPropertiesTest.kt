package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.CatalogClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class CatalogClientPropertiesTest {
    @Test
    fun `protected deployments require an HTTPS service URL`() {
        assertThat(properties().isSafeFor("production")).isTrue()
        assertThat(
            properties().copy(baseUrl = URI("http://catalog.internal"))
                .isSafeFor("production"),
        ).isFalse()
        assertThat(
            properties().copy(baseUrl = URI("http://localhost:8082"))
                .isSafeFor("local"),
        ).isTrue()
    }

    @Test
    fun `service URL cannot carry credentials paths or query parameters`() {
        assertThat(properties().copy(baseUrl = URI("https://user@catalog.internal")).isValid).isFalse()
        assertThat(properties().copy(baseUrl = URI("https://catalog.internal/api")).isValid).isFalse()
        assertThat(properties().copy(baseUrl = URI("https://catalog.internal?debug=true")).isValid).isFalse()
    }

    @Test
    fun `timeouts and response limits stay bounded`() {
        assertThat(properties().copy(connectTimeout = Duration.ofMillis(99)).isValid).isFalse()
        assertThat(properties().copy(readTimeout = Duration.ofSeconds(11)).isValid).isFalse()
        assertThat(properties().copy(maximumResponseBytes = 16_383).isValid).isFalse()
        assertThat(properties().copy(maximumResponseBytes = 1_048_577).isValid).isFalse()
        assertThat(properties().copy(maximumDelegatedTokenLifetime = Duration.ofSeconds(29)).isValid).isFalse()
        assertThat(properties().copy(maximumDelegatedTokenLifetime = Duration.ofMinutes(11)).isValid).isFalse()
        assertThat(properties().copy(audience = "bad audience").isValid).isFalse()
    }

    private fun properties() = CatalogClientProperties(
        baseUrl = URI("https://catalog.internal"),
        audience = "catalog-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 512 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )
}
