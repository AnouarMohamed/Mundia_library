package com.mundiapolis.library.bff.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("app.clients.catalog")
data class CatalogClientProperties(
    val baseUrl: URI,
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val maximumResponseBytes: Int,
) {
    @get:AssertTrue(message = "Catalog client configuration is invalid")
    val isValid: Boolean
        get() = baseUrl.userInfo == null &&
            baseUrl.query == null &&
            baseUrl.fragment == null &&
            baseUrl.path.isEmpty() &&
            !baseUrl.host.isNullOrBlank() &&
            baseUrl.scheme in setOf("http", "https") &&
            connectTimeout in MINIMUM_TIMEOUT..MAXIMUM_CONNECT_TIMEOUT &&
            readTimeout in MINIMUM_TIMEOUT..MAXIMUM_READ_TIMEOUT &&
            maximumResponseBytes in MINIMUM_RESPONSE_BYTES..MAXIMUM_RESPONSE_BYTES

    fun isSafeFor(deploymentTier: String): Boolean =
        isValid && (deploymentTier == "local" || baseUrl.scheme == "https")

    private companion object {
        val MINIMUM_TIMEOUT: Duration = Duration.ofMillis(100)
        val MAXIMUM_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val MAXIMUM_READ_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val MINIMUM_RESPONSE_BYTES = 16 * 1024
        const val MAXIMUM_RESPONSE_BYTES = 1024 * 1024
    }
}
