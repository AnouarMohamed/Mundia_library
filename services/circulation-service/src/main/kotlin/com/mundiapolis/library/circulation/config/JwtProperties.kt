package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("app.security.jwt")
data class JwtProperties(
    @field:NotBlank val issuer: String,
    @field:NotBlank val jwkSetUri: String,
    @field:NotBlank val audience: String,
)
