package com.mundiapolis.library.bff

import jakarta.servlet.http.Cookie
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.session.web.http.CookieSerializer
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class)
class BffRedisSessionIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var sessions: SessionRepository<*>

    @Autowired
    private lateinit var cookieSerializer: CookieSerializer

    @Test
    fun `authenticated security context survives across requests in redis`() {
        val session = sessionRepository().createSession()
        val now = Instant.now()
        val idToken = OidcIdToken(
            "integration-test-token",
            now,
            now.plusSeconds(60),
            mapOf("sub" to "member-1", "name" to "Redis Member"),
        )
        val authentication = OAuth2AuthenticationToken(
            DefaultOidcUser(emptyList(), idToken),
            emptyList(),
            "institutional",
        )
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            SecurityContextImpl(authentication),
        )
        sessionRepository().save(session)
        val sessionCookie = serializeCookie(session.id)

        mockMvc.perform(get("/api/v1/auth/session").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.displayName").value("Redis Member"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessionRepository(): SessionRepository<Session> =
        sessions as SessionRepository<Session>

    private fun serializeCookie(sessionId: String): Cookie {
        val response = MockHttpServletResponse()
        cookieSerializer.writeCookieValue(
            CookieSerializer.CookieValue(MockHttpServletRequest(), response, sessionId),
        )
        val header = requireNotNull(response.getHeader("Set-Cookie"))
        check("; Path=/" in header)
        check("; Secure" in header)
        check("; HttpOnly" in header)
        check("; SameSite=Lax" in header)
        val nameAndValue = header.substringBefore(';').split('=', limit = 2)
        check(nameAndValue[0] == "MUNDIA_SESSION")
        return Cookie(nameAndValue[0], nameAndValue[1])
    }

    private companion object {
        @Container
        @JvmStatic
        val redis = GenericContainer(
            DockerImageName.parse(
                "redis:8.2-alpine@sha256:b51665e66f00759be7c3152ad5ac3c66fb2f619c13ef62dea7cc1f9914524635",
            ),
        ).withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") {
                "redis://${redis.host}:${redis.getMappedPort(6379)}"
            }
            registry.add("OIDC_ISSUER") { "https://issuer.example.test" }
            registry.add("OIDC_CLIENT_ID") { "web-bff-test" }
            registry.add("OIDC_CLIENT_SECRET") { "integration-test-only" }
            registry.add("CATALOG_SERVICE_URL") { "https://catalog.example.test" }
            registry.add("MEMBERSHIP_SERVICE_URL") { "https://membership.example.test" }
            registry.add("CIRCULATION_SERVICE_URL") { "https://circulation.example.test" }
            registry.add("DEPLOYMENT_TIER") { "production" }
            registry.add("BFF_PUBLIC_BASE_URL") { "https://library.example.test" }
            registry.add("BFF_SECURE_COOKIES") { "true" }
        }
    }
}
