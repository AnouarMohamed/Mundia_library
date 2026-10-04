package com.mundiapolis.library.digitalcontent.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.nio.file.Path
import java.time.Duration

@Validated
@ConfigurationProperties("app.download.cloudfront")
data class CloudFrontProperties(
    val enabled: Boolean,
    val baseUrl: String,
    val keyPairId: String,
    val privateKeyPath: String,
    val urlLifetime: Duration,
) {
    @get:AssertTrue(message = "CloudFront download signing configuration is invalid")
    val isValid: Boolean
        get() = urlLifetime in MINIMUM_LIFETIME..MAXIMUM_LIFETIME &&
            (!enabled || enabledConfigurationValid())

    fun baseUri(): URI = URI(baseUrl)

    fun keyPath(): Path = Path.of(privateKeyPath)

    private fun enabledConfigurationValid(): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        val path = runCatching { Path.of(privateKeyPath) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            uri.port == -1 &&
            baseUrl == baseUrl.trim() &&
            KEY_PAIR_ID.matches(keyPairId) &&
            path.isAbsolute
    }

    private companion object {
        val KEY_PAIR_ID = Regex("^[A-Z0-9]{8,64}$")
        val MINIMUM_LIFETIME: Duration = Duration.ofSeconds(15)
        val MAXIMUM_LIFETIME: Duration = Duration.ofMinutes(5)
    }
}
