package com.mundiapolis.library.bff.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("app.clients.circulation")
data class CirculationClientProperties(
    val baseUrl: URI,
    val audience: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val maximumResponseBytes: Int,
    val maximumDelegatedTokenLifetime: Duration,
) {
    @get:AssertTrue(message = "Circulation client configuration is invalid")
    val isValid: Boolean
        get() = baseUrl.userInfo == null &&
            baseUrl.query == null &&
            baseUrl.fragment == null &&
            baseUrl.path.isEmpty() &&
            !baseUrl.host.isNullOrBlank() &&
            baseUrl.scheme in setOf("http", "https") &&
            audience.length in 1..MAXIMUM_AUDIENCE_LENGTH &&
            audience.none { it.isWhitespace() || it.isISOControl() } &&
            connectTimeout in MINIMUM_TIMEOUT..MAXIMUM_CONNECT_TIMEOUT &&
            readTimeout in MINIMUM_TIMEOUT..MAXIMUM_READ_TIMEOUT &&
            maximumResponseBytes in MINIMUM_RESPONSE_BYTES..MAXIMUM_RESPONSE_BYTES &&
            maximumDelegatedTokenLifetime in MINIMUM_TOKEN_LIFETIME..MAXIMUM_TOKEN_LIFETIME

    fun isSafeFor(deploymentTier: String): Boolean =
        isValid && (deploymentTier == "local" || baseUrl.scheme == "https")

    private companion object {
        val MINIMUM_TIMEOUT: Duration = Duration.ofMillis(100)
        val MAXIMUM_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val MAXIMUM_READ_TIMEOUT: Duration = Duration.ofSeconds(10)
        val MINIMUM_TOKEN_LIFETIME: Duration = Duration.ofSeconds(30)
        val MAXIMUM_TOKEN_LIFETIME: Duration = Duration.ofMinutes(10)
        const val MINIMUM_RESPONSE_BYTES = 4 * 1024
        const val MAXIMUM_RESPONSE_BYTES = 256 * 1024
        const val MAXIMUM_AUDIENCE_LENGTH = 200
    }
}
