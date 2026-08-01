package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.AssertTrue
import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("app.security.jwt")
data class JwtProperties(
    @field:NotBlank val issuer: String,
    @field:NotBlank val jwkSetUri: String,
    @field:NotBlank val audience: String,
) {
    @get:AssertTrue(
        message =
            "JWT issuer and JWK set URI must be exact HTTPS URLs on the same trusted origin " +
                "without credentials, query, or fragment",
    )
    val trustedEndpoints: Boolean
        get() {
            val issuerUri = issuer.toStrictHttpsUri() ?: return false
            val jwksUri = jwkSetUri.toStrictHttpsUri() ?: return false
            return issuerUri.host.equals(jwksUri.host, ignoreCase = true) &&
                issuerUri.effectivePort() == jwksUri.effectivePort()
        }

    private fun String.toStrictHttpsUri(): URI? =
        runCatching { URI(this) }
            .getOrNull()
            ?.takeIf {
                it.scheme == "https" &&
                    !it.host.isNullOrBlank() &&
                    it.rawUserInfo == null &&
                    it.rawQuery == null &&
                    it.rawFragment == null &&
                    trim() == this
            }

    private fun URI.effectivePort(): Int = if (port == -1) 443 else port
}
