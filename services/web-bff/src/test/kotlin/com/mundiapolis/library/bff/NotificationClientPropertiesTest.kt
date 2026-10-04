package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.NotificationClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class NotificationClientPropertiesTest {
    @Test
    fun `protected deployments require https and bounded delegated access`() {
        assertThat(properties().isSafeFor("production")).isTrue()
        assertThat(properties().copy(baseUrl = URI("http://notification.internal")).isSafeFor("production"))
            .isFalse()
        assertThat(properties().copy(audience = "notification api").isValid).isFalse()
        assertThat(properties().copy(readTimeout = Duration.ofSeconds(11)).isValid).isFalse()
        assertThat(properties().copy(maximumDelegatedTokenLifetime = Duration.ofMinutes(11)).isValid)
            .isFalse()
    }

    private fun properties() = NotificationClientProperties(
        baseUrl = URI("https://notification.internal"),
        audience = "notification-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(5),
        maximumResponseBytes = 256 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )
}
