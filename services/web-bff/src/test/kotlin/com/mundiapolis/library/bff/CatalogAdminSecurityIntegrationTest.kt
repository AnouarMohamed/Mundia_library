package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.catalog.CatalogAdminController
import com.mundiapolis.library.bff.catalog.CatalogAdminUseCase
import com.mundiapolis.library.bff.catalog.CatalogCommandView
import com.mundiapolis.library.bff.catalog.CatalogMutationResult
import com.mundiapolis.library.bff.catalog.CatalogSearchView
import com.mundiapolis.library.bff.catalog.CatalogWorkView
import com.mundiapolis.library.bff.catalog.CreateCatalogEditionView
import com.mundiapolis.library.bff.catalog.CreateCatalogWorkView
import com.mundiapolis.library.bff.catalog.SetCatalogEditionActiveView
import com.mundiapolis.library.bff.catalog.UpdateCatalogEditionView
import com.mundiapolis.library.bff.catalog.UpdateCatalogWorkView
import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
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
    controllers = [CatalogAdminController::class],
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
    CatalogAdminSecurityIntegrationTest.AdminTestConfiguration::class,
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class CatalogAdminSecurityIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    fun `catalog administration requires a browser session and is never cached`() {
        mockMvc.perform(get("/api/v1/admin/catalog/editions"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/v1/admin/catalog/editions").with(oidcLogin()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.total").value(0))
    }

    @Test
    fun `catalog commands require csrf and preserve concurrency headers`() {
        val request = post("/api/v1/admin/catalog/editions/$EDITION_ID/activation")
            .with(oidcLogin())
            .header("If-Match", "\"3\"")
            .header("Idempotency-Key", "catalog-activation-0001")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"isActive":false,"reason":"Edition withdrawn by catalog administrator"}""")

        mockMvc.perform(request).andExpect(status().isForbidden)
        mockMvc.perform(request.with(csrf()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("ETag", "\"4\""))
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.aggregateId").value(EDITION_ID.toString()))
            .andExpect(jsonPath("$.aggregateVersion").value(4))
    }

    @Test
    fun `catalog commands reject weak or unquoted versions`() {
        mockMvc.perform(
            post("/api/v1/admin/catalog/editions/$EDITION_ID/activation")
                .with(oidcLogin()).with(csrf())
                .header("If-Match", "W/\"3\"")
                .header("Idempotency-Key", "catalog-activation-0002")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"isActive":false,"reason":"Edition withdrawn by catalog administrator"}"""),
        ).andExpect(status().isBadRequest)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class AdminTestConfiguration {
        @Bean
        fun catalogAdminUseCase(): CatalogAdminUseCase = object : CatalogAdminUseCase {
            override fun editions(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, query: String?, page: Int?, limit: Int?) = CatalogSearchView(emptyList(), 0, 0, 0)
            override fun work(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID): CatalogWorkView = error("not used")
            override fun createWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, key: String, command: CreateCatalogWorkView): CatalogMutationResult = error("not used")
            override fun updateWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID, version: Long, key: String, command: UpdateCatalogWorkView): CatalogMutationResult = error("not used")
            override fun createEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID, key: String, command: CreateCatalogEditionView): CatalogMutationResult = error("not used")
            override fun updateEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionId: UUID, version: Long, key: String, command: UpdateCatalogEditionView): CatalogMutationResult = error("not used")
            override fun setActive(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionId: UUID, version: Long, key: String, command: SetCatalogEditionActiveView) =
                CatalogMutationResult(CatalogCommandView("EDITION", editionId, version + 1, NOW), false)
        }
    }

    companion object {
        val EDITION_ID: UUID = UUID.fromString("23000000-0000-4000-8000-000000000001")
        val NOW: Instant = Instant.parse("2026-10-08T10:00:00Z")
    }
}
