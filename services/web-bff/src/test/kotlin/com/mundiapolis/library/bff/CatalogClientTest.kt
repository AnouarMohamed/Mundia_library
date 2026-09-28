package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.catalog.CatalogClient
import com.mundiapolis.library.bff.catalog.CatalogProtocolException
import com.mundiapolis.library.bff.catalog.CatalogSearchCriteria
import com.mundiapolis.library.bff.catalog.CatalogUnavailableException
import com.mundiapolis.library.bff.config.CatalogClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.time.Duration

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
            .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON))

        val result = client.search(
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

        assertThatThrownBy { client.search(criteria()) }
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

        assertThatThrownBy { client.search(criteria()) }
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

        assertThatThrownBy { client.search(criteria()) }
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

        assertThatThrownBy { client.search(criteria()) }
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
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 16 * 1024,
    )

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
    }
}
