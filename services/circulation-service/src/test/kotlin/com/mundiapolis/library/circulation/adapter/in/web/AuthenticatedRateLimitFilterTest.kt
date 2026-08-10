package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.RateLimitDecision
import com.mundiapolis.library.circulation.application.port.outbound.RateLimitStore
import com.mundiapolis.library.circulation.config.RateLimitProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import tools.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant

class AuthenticatedRateLimitFilterTest {
    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `exhausted sensitive budget returns standard 429 without invoking application`() {
        authenticate()
        val reset = Instant.now().plusSeconds(45)
        val filter = filter(
            RateLimitStore { _, bucket, limit, _, _ ->
                assertThat(bucket).isEqualTo("sensitive")
                assertThat(limit).isEqualTo(30)
                RateLimitDecision(false, limit, 0, reset)
            },
        )
        val request = MockHttpServletRequest("POST", "/api/v1/circulation/loans/id/approve")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertThat(response.status).isEqualTo(429)
        assertThat(response.getHeader("Retry-After")?.toLong()).isBetween(1, 45)
        assertThat(response.getHeader("RateLimit-Reset")).isEqualTo(response.getHeader("Retry-After"))
        assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("30")
        assertThat(response.contentAsString).contains("rate_limit_exceeded")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `rate limit storage failure fails closed`() {
        authenticate()
        val filter = filter(RateLimitStore { _, _, _, _, _ -> error("database unavailable") })
        val request = MockHttpServletRequest("GET", "/api/v1/circulation/policy")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(response.status).isEqualTo(503)
        assertThat(response.contentAsString).contains("rate_limit_unavailable")
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
    }

    private fun filter(store: RateLimitStore): AuthenticatedRateLimitFilter =
        AuthenticatedRateLimitFilter(
            store,
            RateLimitProperties(
                enabled = true,
                window = Duration.ofMinutes(1),
                readRequests = 600,
                commandRequests = 120,
                sensitiveRequests = 30,
                cleanupInterval = Duration.ofHours(1),
                cleanupBatchSize = 5000,
            ),
            ObjectMapper(),
            SimpleMeterRegistry(),
        )

    private fun authenticate() {
        val jwt = Jwt.withTokenValue("signed-test-token")
            .header("alg", "RS256")
            .issuer("https://issuer.example.test")
            .subject("rate-limited-user")
            .claim("client_id", "circulation-test")
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(300))
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(
            jwt,
            listOf(SimpleGrantedAuthority("SCOPE_circulation.loan.approve")),
        )
    }
}
