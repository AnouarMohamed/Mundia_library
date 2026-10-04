package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.DigitalContentClientProperties
import com.mundiapolis.library.bff.digitalcontent.DigitalContentClient
import com.mundiapolis.library.bff.digitalcontent.DigitalContentProtocolException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DigitalContentClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: DigitalContentClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl("https://digital-content.internal")
        server = MockRestServiceServer.bindTo(builder).build()
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .findAndAddModules()
            .build()
        client = DigitalContentClient(
            builder.build(),
            mapper,
            properties(),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
    }

    @Test
    fun `availability forwards delegated authorization and validates formats`() {
        server.expect(
            requestTo("https://digital-content.internal/api/v1/digital-content/editions/$EDITION_ID/availability"),
        )
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andRespond(withSuccess(AVAILABILITY, MediaType.APPLICATION_JSON))

        val result = client.availability(authorizedClient(), EDITION_ID)

        assertThat(result.downloadable).isTrue()
        assertThat(result.formats.single().assetId).isEqualTo(ASSET_ID)
        server.verify()
    }

    @Test
    fun `authorization accepts only the configured cloudfront origin and signing shape`() {
        server.expect(
            requestTo("https://digital-content.internal/api/v1/digital-content/assets/$ASSET_ID/authorizations"),
        )
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(AUTHORIZATION, MediaType.APPLICATION_JSON))

        val result = client.authorize(authorizedClient(), ASSET_ID)
        assertThat(result.expiresAt).isEqualTo(NOW.plusSeconds(60))
        server.verify()
    }

    @Test
    fun `authorization rejects a downstream supplied attacker origin`() {
        server.expect(
            requestTo("https://digital-content.internal/api/v1/digital-content/assets/$ASSET_ID/authorizations"),
        ).andRespond(
            withSuccess(
                AUTHORIZATION.replace("downloads.example.test", "attacker.example"),
                MediaType.APPLICATION_JSON,
            ),
        )

        assertThatThrownBy { client.authorize(authorizedClient(), ASSET_ID) }
            .isInstanceOf(DigitalContentProtocolException::class.java)
        server.verify()
    }

    private fun authorizedClient(): OAuth2AuthorizedClient {
        val registration = ClientRegistration.withRegistrationId("digital-content-service")
            .clientId("web-bff-digital-content")
            .clientSecret("test-only-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .tokenUri("https://issuer.example.test/oauth2/token")
            .build()
        return OAuth2AuthorizedClient(
            registration,
            "member",
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "delegated-token",
                NOW,
                NOW.plusSeconds(120),
            ),
        )
    }

    private fun properties() = DigitalContentClientProperties(
        URI("https://digital-content.internal"),
        "digital-content-api",
        URI("https://downloads.example.test"),
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        64 * 1024,
        Duration.ofMinutes(5),
        Duration.ofMinutes(5),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-04T12:00:00Z")
        val EDITION_ID: UUID = UUID.fromString("11000000-0000-0000-0000-000000000001")
        val ASSET_ID: UUID = UUID.fromString("12000000-0000-0000-0000-000000000001")
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val AVAILABILITY = """
            {"editionId":"$EDITION_ID","downloadable":true,"formats":[{"assetId":"$ASSET_ID","format":"PDF","mediaType":"application/pdf","sizeBytes":4096,"sha256":"$DIGEST","licenseExpression":"CC-BY-4.0","attribution":"Example Engineering Text"}]}
        """.trimIndent()
        val AUTHORIZATION = """
            {"authorizationId":"13000000-0000-0000-0000-000000000001","assetId":"$ASSET_ID","downloadUrl":"https://downloads.example.test/digital-content/ab/$ASSET_ID/$DIGEST.pdf?Policy=abc_&Signature=def_&Key-Pair-Id=K12345678&Hash-Algorithm=SHA256","expiresAt":"2026-10-04T12:01:00Z"}
        """.trimIndent()
    }
}
