package com.mundiapolis.library.bff.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("app.bff")
data class BffProperties(
    val deploymentTier: String,
    val publicBaseUrl: URI,
    val secureCookies: Boolean,
    val sessionMaximumAge: Duration,
) {
    @get:AssertTrue(message = "BFF deployment and session configuration is unsafe")
    val isSafeConfiguration: Boolean
        get() {
            if (deploymentTier !in DEPLOYMENT_TIERS) return false
            if (
                publicBaseUrl.userInfo != null ||
                publicBaseUrl.query != null ||
                publicBaseUrl.fragment != null ||
                publicBaseUrl.path.isNotEmpty() ||
                publicBaseUrl.host.isNullOrBlank() ||
                sessionMaximumAge !in MINIMUM_SESSION_AGE..MAXIMUM_SESSION_AGE
            ) return false
            return if (deploymentTier == "local") {
                publicBaseUrl.scheme in setOf("http", "https")
            } else {
                publicBaseUrl.scheme == "https" && secureCookies
            }
        }

    private companion object {
        val DEPLOYMENT_TIERS = setOf("local", "staging", "production")
        val MINIMUM_SESSION_AGE: Duration = Duration.ofMinutes(5)
        val MAXIMUM_SESSION_AGE: Duration = Duration.ofHours(8)
    }
}
