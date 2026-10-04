package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import com.mundiapolis.library.bff.digitalcontent.DigitalContentController
import com.mundiapolis.library.bff.digitalcontent.DigitalContentSelfServiceUseCase
import com.mundiapolis.library.bff.digitalcontent.DownloadAuthorizationView
import com.mundiapolis.library.bff.digitalcontent.EditionDownloadAvailabilityView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [DigitalContentController::class],
    properties = [
        "app.bff.deployment-tier=local",
        "app.bff.public-base-url=http://localhost",
        "app.bff.secure-cookies=false",
        "app.bff.session-maximum-age=PT8H",
    ],
)
@EnableConfigurationProperties(BffProperties::class)
@Import(
    SecurityConfiguration::class,
    DigitalContentSecurityIntegrationTest.DigitalContentTestConfiguration::class,
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class DigitalContentSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `digital content routes require a browser session`() {
        mockMvc.perform(get("/api/v1/digital-content/editions/$EDITION_ID/availability"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/v1/digital-content/assets/$ASSET_ID/authorizations"))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            post("/api/v1/digital-content/assets/$ASSET_ID/authorizations").with(csrf()),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `download authorization requires csrf and is never cacheable`() {
        val path = "/api/v1/digital-content/assets/$ASSET_ID/authorizations"
        mockMvc.perform(post(path).with(oidcLogin()))
            .andExpect(status().isForbidden)

        mockMvc.perform(post(path).with(oidcLogin()).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.assetId").value(ASSET_ID.toString()))
            .andExpect(jsonPath("$.downloadUrl").value("https://downloads.example.test/signed"))
    }

    @Test
    fun `browser availability is not cached`() {
        mockMvc.perform(
            get("/api/v1/digital-content/editions/$EDITION_ID/availability").with(oidcLogin()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.downloadable").value(false))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class DigitalContentTestConfiguration {
        @Bean
        fun digitalContentUseCase(): DigitalContentSelfServiceUseCase = FakeDigitalContentUseCase()
    }

    private class FakeDigitalContentUseCase : DigitalContentSelfServiceUseCase {
        override fun availability(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            editionId: UUID,
        ) = EditionDownloadAvailabilityView(editionId, false, emptyList())

        override fun authorize(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            assetId: UUID,
        ) = DownloadAuthorizationView(
            AUTHORIZATION_ID,
            assetId,
            "https://downloads.example.test/signed",
            Instant.parse("2026-10-04T12:01:00Z"),
        )
    }

    private companion object {
        val EDITION_ID: UUID = UUID.fromString("11000000-0000-0000-0000-000000000001")
        val ASSET_ID: UUID = UUID.fromString("12000000-0000-0000-0000-000000000001")
        val AUTHORIZATION_ID: UUID = UUID.fromString("13000000-0000-0000-0000-000000000001")
    }
}
