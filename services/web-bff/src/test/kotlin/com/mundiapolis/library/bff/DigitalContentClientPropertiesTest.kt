package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.DigitalContentClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class DigitalContentClientPropertiesTest {
    @Test
    fun `production requires https service transport and an https download origin`() {
        assertThat(properties().isSafeFor("production")).isTrue()
        assertThat(properties(serviceUrl = "http://digital-content.internal").isSafeFor("production")).isFalse()
        assertThat(properties(downloadUrl = "http://downloads.example.test").isValid).isFalse()
        assertThat(properties(downloadUrl = "https://downloads.example.test/path").isValid).isFalse()
    }

    @Test
    fun `trusted download requires the exact configured origin and normalized path`() {
        val properties = properties()
        assertThat(properties.isTrustedDownload(URI("https://downloads.example.test/digital-content/file.pdf?x=1")))
            .isTrue()
        assertThat(properties.isTrustedDownload(URI("https://downloads.example.test.attacker.invalid/file.pdf?x=1")))
            .isFalse()
        assertThat(properties.isTrustedDownload(URI("https://downloads.example.test/a/../file.pdf?x=1")))
            .isFalse()
    }

    private fun properties(
        serviceUrl: String = "https://digital-content.internal",
        downloadUrl: String = "https://downloads.example.test",
    ) = DigitalContentClientProperties(
        URI(serviceUrl),
        "digital-content-api",
        URI(downloadUrl),
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        64 * 1024,
        Duration.ofMinutes(5),
        Duration.ofMinutes(5),
    )
}
