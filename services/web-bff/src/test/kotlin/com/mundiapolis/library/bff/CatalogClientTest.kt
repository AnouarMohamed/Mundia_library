package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.catalog.CatalogClient
import com.mundiapolis.library.bff.catalog.CatalogProtocolException
import com.mundiapolis.library.bff.catalog.CatalogSearchCriteria
import com.mundiapolis.library.bff.catalog.CatalogUnavailableException
import com.mundiapolis.library.bff.catalog.LearningResourceSearchCriteria
import com.mundiapolis.library.bff.config.CatalogClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.time.Duration
import java.time.Instant

class CatalogClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: CatalogClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl("https://catalog.internal")
        server = MockRestServiceServer.bindTo(builder).build()
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()
        client = CatalogClient(builder.build(), mapper, properties())
    }

    @Test
    fun `search forwards bounded filters and decodes the service contract`() {
        server.expect(requestTo(containsString("/api/v1/catalog/search?query=distributed%20systems")))
            .andExpect(requestTo(containsString("availableOnly=true")))
            .andExpect(requestTo(containsString("page=0")))
            .andExpect(requestTo(containsString("limit=20")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON))

        val result = client.search(
            authorizedClient(),
            criteria(query = "distributed systems", availableOnly = true, page = 0, limit = 20),
        )

        assertThat(result.total).isEqualTo(1)
        assertThat(result.editions.single().title).isEqualTo("Distributed Systems")
        server.verify()
    }

    @Test
    fun `retryable downstream status becomes service unavailable`() {
        server.expect(requestTo(containsString("/api/v1/catalog/search")))
            .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))

        assertThatThrownBy { client.search(authorizedClient(), criteria()) }
            .isInstanceOf(CatalogUnavailableException::class.java)
        server.verify()
    }

    @Test
    fun `invalid inventory values are rejected as a protocol failure`() {
        server.expect(requestTo(containsString("/api/v1/catalog/search")))
            .andRespond(
                withSuccess(
                    VALID_RESPONSE.replace("\"availableCopies\": 1", "\"availableCopies\": 2"),
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThatThrownBy { client.search(authorizedClient(), criteria()) }
            .isInstanceOf(CatalogProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `inconsistent pagination is rejected as a protocol failure`() {
        server.expect(requestTo(containsString("/api/v1/catalog/search")))
            .andRespond(
                withSuccess(
                    VALID_RESPONSE.replace("\"totalPages\": 1", "\"totalPages\": 2"),
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThatThrownBy { client.search(authorizedClient(), criteria()) }
            .isInstanceOf(CatalogProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `declared oversized responses are rejected before decoding`() {
        server.expect(requestTo(containsString("/api/v1/catalog/search")))
            .andRespond(
                withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_LENGTH, "20000"),
            )

        assertThatThrownBy { client.search(authorizedClient(), criteria()) }
            .isInstanceOf(CatalogProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `learning resource search forwards filters and validates metadata`() {
        server.expect(requestTo(containsString("/api/v1/catalog/learning-resources?query=linux")))
            .andExpect(requestTo(containsString("category=Operating%20Systems")))
            .andExpect(requestTo(containsString("page=0")))
            .andExpect(requestTo(containsString("limit=24")))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andRespond(withSuccess(VALID_RESOURCE_PAGE, MediaType.APPLICATION_JSON))

        val result = client.learningResources(
            authorizedClient(),
            LearningResourceSearchCriteria("linux", "Operating Systems", 0, 24),
        )

        assertThat(result.resources.single().title).isEqualTo("The Linux Command Line")
        server.verify()
    }

    @Test
    fun `learning resource detail must match the requested identity`() {
        server.expect(requestTo("https://catalog.internal/api/v1/catalog/learning-resources/33333333-3333-3333-3333-333333333333"))
            .andRespond(withSuccess(VALID_RESOURCE, MediaType.APPLICATION_JSON))

        assertThatThrownBy {
            client.learningResource(
                authorizedClient(),
                java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"),
            )
        }.isInstanceOf(CatalogProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `learning resource categories must be unique and sorted`() {
        server.expect(requestTo("https://catalog.internal/api/v1/catalog/learning-resource-categories"))
            .andRespond(withSuccess("[\"Security\",\"Cloud\",\"Security\"]", MediaType.APPLICATION_JSON))

        assertThatThrownBy { client.learningResourceCategories(authorizedClient()) }
            .isInstanceOf(CatalogProtocolException::class.java)
        server.verify()
    }

    private fun criteria(
        query: String? = null,
        availableOnly: Boolean? = null,
        page: Int? = null,
        limit: Int? = null,
    ) = CatalogSearchCriteria(
        query = query,
        genre = null,
        authorId = null,
        availableOnly = availableOnly,
        minRating = null,
        sortBy = null,
        page = page,
        limit = limit,
    )

    private fun properties() = CatalogClientProperties(
        baseUrl = URI("https://catalog.internal"),
        audience = "catalog-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 16 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private fun authorizedClient(): OAuth2AuthorizedClient {
        val registration = ClientRegistration.withRegistrationId("catalog-service")
            .clientId("web-bff")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType("urn:ietf:params:oauth:grant-type:token-exchange"))
            .tokenUri("https://issuer.example.test/token")
            .build()
        val now = Instant.now()
        return OAuth2AuthorizedClient(
            registration,
            "user-1",
            OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "delegated-token", now, now.plusSeconds(300)),
        )
    }

    private companion object {
        val VALID_RESPONSE = """
            {
              "editions": [{
                "editionId": "11111111-1111-1111-1111-111111111111",
                "workId": "22222222-2222-2222-2222-222222222222",
                "title": "Distributed Systems",
                "isbn": "9780000000001",
                "publisher": "Mundia Press",
                "publicationYear": 2026,
                "language": "en",
                "pageCount": 320,
                "coverUrl": null,
                "coverColor": null,
                "videoUrl": null,
                "totalCopies": 1,
                "availableCopies": 1,
                "isActive": true
              }],
              "total": 1,
              "page": 0,
              "totalPages": 1
            }
        """.trimIndent()

        val VALID_RESOURCE = """
            {
              "resourceId": "44444444-4444-4444-4444-444444444444",
              "title": "The Linux Command Line",
              "author": "William Shotts",
              "description": "A practical introduction to the command line.",
              "category": "Operating Systems",
              "language": "en",
              "coverUrl": "https://covers.example.org/linux.jpg",
              "coverAlt": "The Linux Command Line cover",
              "sourceName": "Official publisher",
              "sourceUrl": "https://source.example.org/books/linux",
              "licenseExpression": "CC-BY",
              "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
              "accessMode": "DOWNLOAD",
              "readUrl": null
            }
        """.trimIndent()

        val VALID_RESOURCE_PAGE = """
            {
              "resources": [$VALID_RESOURCE],
              "total": 1,
              "page": 0,
              "totalPages": 1
            }
        """.trimIndent()
    }
}
