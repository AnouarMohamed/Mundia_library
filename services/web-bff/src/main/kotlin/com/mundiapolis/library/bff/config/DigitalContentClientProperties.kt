package com.mundiapolis.library.bff.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("app.clients.digital-content")
data class DigitalContentClientProperties(
    val baseUrl: URI,
    val audience: String,
    val downloadBaseUrl: URI,
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val maximumResponseBytes: Int,
    val maximumDelegatedTokenLifetime: Duration,
    val maximumSignedUrlLifetime: Duration,
) {
    @get:AssertTrue(message = "Digital Content client configuration is invalid")
    val isValid: Boolean
        get() = baseUrl.isStrictOrigin(setOf("http", "https")) &&
            downloadBaseUrl.isStrictOrigin(setOf("https")) &&
            AUDIENCE.matches(audience) &&
            connectTimeout in MINIMUM_TIMEOUT..MAXIMUM_CONNECT_TIMEOUT &&
            readTimeout in MINIMUM_TIMEOUT..MAXIMUM_READ_TIMEOUT &&
            maximumResponseBytes in MINIMUM_RESPONSE_BYTES..MAXIMUM_RESPONSE_BYTES &&
            maximumDelegatedTokenLifetime in MINIMUM_TOKEN_LIFETIME..MAXIMUM_TOKEN_LIFETIME &&
            maximumSignedUrlLifetime in MINIMUM_SIGNED_URL_LIFETIME..MAXIMUM_SIGNED_URL_LIFETIME

    fun isSafeFor(deploymentTier: String): Boolean =
        isValid && (deploymentTier == "local" || baseUrl.scheme == "https")

    fun isTrustedDownload(uri: URI): Boolean =
        uri.scheme == "https" &&
            uri.host.equals(downloadBaseUrl.host, ignoreCase = true) &&
            uri.effectivePort() == downloadBaseUrl.effectivePort() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null &&
            !uri.rawPath.isNullOrBlank() &&
            uri.normalize().rawPath == uri.rawPath

    private fun URI.isStrictOrigin(allowedSchemes: Set<String>): Boolean =
        scheme in allowedSchemes &&
            !host.isNullOrBlank() &&
            rawUserInfo == null &&
            rawQuery == null &&
            rawFragment == null &&
            (rawPath.isNullOrEmpty() || rawPath == "/")

    private fun URI.effectivePort(): Int = if (port == -1) 443 else port

    private companion object {
        val AUDIENCE = Regex("^[A-Za-z0-9._:-]{1,128}$")
        val MINIMUM_TIMEOUT: Duration = Duration.ofMillis(100)
        val MAXIMUM_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val MAXIMUM_READ_TIMEOUT: Duration = Duration.ofSeconds(10)
        val MINIMUM_TOKEN_LIFETIME: Duration = Duration.ofSeconds(30)
        val MAXIMUM_TOKEN_LIFETIME: Duration = Duration.ofMinutes(10)
        val MINIMUM_SIGNED_URL_LIFETIME: Duration = Duration.ofSeconds(15)
        val MAXIMUM_SIGNED_URL_LIFETIME: Duration = Duration.ofMinutes(5)
        const val MINIMUM_RESPONSE_BYTES = 16 * 1024
        const val MAXIMUM_RESPONSE_BYTES = 256 * 1024
    }
}
