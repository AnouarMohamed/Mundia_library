package com.mundiapolis.library.bff.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.oauth-token-client")
data class OAuthTokenClientProperties(
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val maximumResponseBytes: Int,
) {
    @get:AssertTrue(message = "OAuth token client configuration is invalid")
    val isValid: Boolean
        get() = connectTimeout in MINIMUM_TIMEOUT..MAXIMUM_CONNECT_TIMEOUT &&
            readTimeout in MINIMUM_TIMEOUT..MAXIMUM_READ_TIMEOUT &&
            maximumResponseBytes in MINIMUM_RESPONSE_BYTES..MAXIMUM_RESPONSE_BYTES

    private companion object {
        val MINIMUM_TIMEOUT: Duration = Duration.ofMillis(100)
        val MAXIMUM_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val MAXIMUM_READ_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val MINIMUM_RESPONSE_BYTES = 4 * 1024
        const val MAXIMUM_RESPONSE_BYTES = 256 * 1024
    }
}
