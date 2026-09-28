package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.catalog.CatalogBrowseController
import com.mundiapolis.library.bff.catalog.CatalogClient
import com.mundiapolis.library.bff.catalog.CatalogEditionView
import com.mundiapolis.library.bff.catalog.CatalogExceptionHandler
import com.mundiapolis.library.bff.catalog.CatalogSearchCriteria
import com.mundiapolis.library.bff.catalog.CatalogSearchView
import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.bean.override.mockito.MockitoBean
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
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class CatalogBrowseSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var catalogClient: CatalogClient

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
        `when`(
            catalogClient.search(
                CatalogSearchCriteria(
                    query = "distributed systems",
                    genre = null,
                    authorId = null,
                    availableOnly = null,
                    minRating = null,
                    sortBy = null,
                    page = null,
                    limit = null,
                ),
            ),
        ).thenReturn(
            CatalogSearchView(
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
            ),
        )

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
}
