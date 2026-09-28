package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import com.mundiapolis.library.bff.web.BffSessionController
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [BffSessionController::class],
    properties = [
        "app.bff.deployment-tier=local",
        "app.bff.public-base-url=http://localhost",
        "app.bff.secure-cookies=false",
        "app.bff.session-maximum-age=PT8H",
    ],
)
@EnableConfigurationProperties(BffProperties::class)
@Import(SecurityConfiguration::class, BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class)
class BffSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `session and csrf bootstrap endpoints are public and never cacheable`() {
        mockMvc.perform(get("/api/v1/auth/session"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.displayName").doesNotExist())

        mockMvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty)

        mockMvc.perform(get("/openapi/web-bff-v1.json"))
            .andExpect(status().isOk)
    }

    @Test
    fun `authenticated session returns only its safe projection`() {
        mockMvc.perform(
            get("/api/v1/auth/session").with(
                oidcLogin().idToken { token -> token.claim("name", "Library Member") },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.displayName").value("Library Member"))
            .andExpect(jsonPath("$.idToken").doesNotExist())
    }

    @Test
    fun `logout requires csrf and returns no redirect body`() {
        mockMvc.perform(post("/api/v1/auth/logout").with(oidcLogin()))
            .andExpect(status().isForbidden)

        mockMvc.perform(post("/api/v1/auth/logout").with(oidcLogin()).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `authorization requests enforce pkce`() {
        mockMvc.perform(get("/oauth2/authorization/institutional"))
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", containsString("code_challenge=")))
            .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClientRegistrationTestConfiguration {
        @Bean
        fun clientRegistrationRepository(): ClientRegistrationRepository =
            InMemoryClientRegistrationRepository(
                ClientRegistration.withRegistrationId("institutional")
                    .clientId("web-bff-test")
                    .clientSecret("integration-test-only")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/login/oauth2/code/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri("https://issuer.example.test/oauth2/authorize")
                    .tokenUri("https://issuer.example.test/oauth2/token")
                    .jwkSetUri("https://issuer.example.test/.well-known/jwks.json")
                    .issuerUri("https://issuer.example.test")
                    .userInfoUri("https://issuer.example.test/oauth2/userinfo")
                    .userNameAttributeName("sub")
                    .clientName("Institutional OIDC")
                    .build(),
            )
    }
}
