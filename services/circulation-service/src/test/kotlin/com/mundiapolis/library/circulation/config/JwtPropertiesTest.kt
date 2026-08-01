package com.mundiapolis.library.circulation.config

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JwtPropertiesTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `accepts exact same-origin HTTPS issuer and JWKS endpoints`() {
        val properties =
            JwtProperties(
                issuer = "https://identity.example.test/tenant",
                jwkSetUri = "https://identity.example.test/tenant/.well-known/jwks.json",
                audience = "circulation-api",
            )

        assertThat(validator.validate(properties)).isEmpty()
    }

    @Test
    fun `rejects insecure ambiguous and cross-origin trust endpoints`() {
        val invalidEndpoints =
            listOf(
                "http://identity.example.test/jwks.json",
                "https://attacker.example.test/jwks.json",
                "https://identity.example.test/jwks.json?tenant=attacker",
                "https://user@identity.example.test/jwks.json",
                " https://identity.example.test/jwks.json",
            )

        invalidEndpoints.forEach { jwkSetUri ->
            val properties =
                JwtProperties(
                    issuer = "https://identity.example.test/tenant",
                    jwkSetUri = jwkSetUri,
                    audience = "circulation-api",
                )

            assertThat(validator.validate(properties))
                .describedAs("validation errors for %s", jwkSetUri)
                .isNotEmpty()
        }
    }
}
