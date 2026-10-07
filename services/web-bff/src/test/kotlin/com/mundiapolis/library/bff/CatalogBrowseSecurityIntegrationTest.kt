package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.catalog.CatalogBrowseController
import com.mundiapolis.library.bff.catalog.CatalogBrowseUseCase
import com.mundiapolis.library.bff.catalog.CatalogEditionView
import com.mundiapolis.library.bff.catalog.CatalogExceptionHandler
import com.mundiapolis.library.bff.catalog.CatalogSearchCriteria
import com.mundiapolis.library.bff.catalog.CatalogSearchView
import com.mundiapolis.library.bff.catalog.LearningResourcePageView
import com.mundiapolis.library.bff.catalog.LearningResourceSearchCriteria
import com.mundiapolis.library.bff.catalog.LearningResourceView
import com.mundiapolis.library.bff.catalog.LearningResourceAccessMode
import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import org.junit.jupiter.api.Test
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [CatalogBrowseController::class],
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
    CatalogExceptionHandler::class,
    CatalogBrowseSecurityIntegrationTest.CatalogTestConfiguration::class,
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class CatalogBrowseSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `catalog search requires an authenticated browser session`() {
        mockMvc.perform(get("/api/v1/catalog/search"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `catalog search validates its public query contract`() {
        mockMvc.perform(
            get("/api/v1/catalog/search")
                .param("limit", "101")
                .with(oidcLogin()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_catalog_search"))
    }

    @Test
    fun `catalog search returns only the mapped no-store response`() {
        mockMvc.perform(
            get("/api/v1/catalog/search")
                .param("query", "distributed systems")
                .with(oidcLogin()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.editions[0].title").value("Distributed Systems"))
            .andExpect(jsonPath("$.editions[0].editionId").value("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun `learning resource routes require a session and remain non cacheable`() {
        mockMvc.perform(get("/api/v1/catalog/learning-resources"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/api/v1/catalog/learning-resources")
                .param("category", "Operating Systems")
                .with(oidcLogin()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.resources[0].title").value("The Linux Command Line"))

        mockMvc.perform(get("/api/v1/catalog/learning-resource-categories").with(oidcLogin()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$[0]").value("Operating Systems"))
    }

    @Test
    fun `edition batch requires a session and remains bounded and non cacheable`() {
        mockMvc.perform(get("/api/v1/catalog/editions").param("editionId", EDITION_ID.toString()))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/api/v1/catalog/editions")
                .param("editionId", EDITION_ID.toString())
                .with(oidcLogin()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$[0].editionId").value(EDITION_ID.toString()))
            .andExpect(jsonPath("$[0].title").value("Distributed Systems"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CatalogTestConfiguration {
        @Bean
        fun catalogBrowseUseCase(): CatalogBrowseUseCase = object : CatalogBrowseUseCase {
            override fun search(
                authentication: OAuth2AuthenticationToken,
                request: HttpServletRequest,
                response: HttpServletResponse,
                criteria: CatalogSearchCriteria,
            ) = CatalogSearchView(
                editions = listOf(
                    CatalogEditionView(
                        editionId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        workId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        title = "Distributed Systems",
                        isbn = "9780000000001",
                        publisher = "Mundia Press",
                        publicationYear = 2026,
                        language = "en",
                        pageCount = 320,
                        coverUrl = null,
                        coverColor = null,
                        videoUrl = null,
                        totalCopies = 1,
                        availableCopies = 1,
                        isActive = true,
                    ),
                ),
                total = 1,
                page = 0,
                totalPages = 1,
            )

            override fun learningResources(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, criteria: LearningResourceSearchCriteria): LearningResourcePageView =
                LearningResourcePageView(listOf(resource()), 1, 0, 1)

            override fun learningResource(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, resourceId: UUID): LearningResourceView = resource()

            override fun learningResourceCategories(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse): List<String> = listOf("Operating Systems")

            override fun editions(
                authentication: OAuth2AuthenticationToken,
                request: HttpServletRequest,
                response: HttpServletResponse,
                editionIds: List<UUID>,
            ): List<CatalogEditionView> = listOf(
                CatalogEditionView(
                    editionId = EDITION_ID,
                    workId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    title = "Distributed Systems",
                    isbn = "9780000000001",
                    publisher = "Mundia Press",
                    publicationYear = 2026,
                    language = "en",
                    pageCount = 320,
                    coverUrl = null,
                    coverColor = null,
                    videoUrl = null,
                    totalCopies = 1,
                    availableCopies = 1,
                    isActive = true,
                ),
            )

            private fun resource() = LearningResourceView(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "The Linux Command Line",
                "William Shotts",
                "A practical introduction to the command line.",
                "Operating Systems",
                "en",
                "https://covers.example.org/linux.jpg",
                "The Linux Command Line cover",
                "Official publisher",
                "https://source.example.org/books/linux",
                "CC-BY",
                "https://creativecommons.org/licenses/by/4.0/",
                LearningResourceAccessMode.DOWNLOAD,
                null,
            )
        }
    }

    private companion object {
        val EDITION_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
