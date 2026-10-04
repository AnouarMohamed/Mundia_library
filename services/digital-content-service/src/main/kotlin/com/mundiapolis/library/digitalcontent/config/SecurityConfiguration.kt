package com.mundiapolis.library.digitalcontent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                it.requestMatchers("/openapi/**").permitAll()
                it.requestMatchers("/actuator/**").hasAuthority("SCOPE_digital-content.operations.read")
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt {} }
        return http.build()
    }

    @Bean
    fun jwtDecoder(properties: JwtProperties): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build()
        val validators = DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(properties.issuer),
            audienceValidator(properties.audience),
            typeValidator(),
        )
        decoder.setJwtValidator(validators)
        return decoder
    }

    private fun audienceValidator(audience: String) = OAuth2TokenValidator<Jwt> { jwt ->
        if (jwt.audience?.contains(audience) == true) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "Required audience is missing", null),
            )
        }
    }

    private fun typeValidator() = OAuth2TokenValidator<Jwt> { jwt ->
        if (jwt.headers["typ"] == "JWT") {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "JWT typ header must be JWT", null),
            )
        }
    }
}
