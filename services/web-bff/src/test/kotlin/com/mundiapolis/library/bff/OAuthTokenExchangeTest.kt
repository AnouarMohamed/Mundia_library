package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.CirculationClientProperties
import com.mundiapolis.library.bff.config.CatalogClientProperties
import com.mundiapolis.library.bff.config.DigitalContentClientProperties
import com.mundiapolis.library.bff.config.MembershipClientProperties
import com.mundiapolis.library.bff.config.NotificationClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration
import com.mundiapolis.library.bff.config.withOAuthTokenProtocolSupport
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.endpoint.TokenExchangeGrantRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration
import java.time.Instant

class OAuthTokenExchangeTest {
    @Test
    fun `catalog token exchange requests only its audience and read scopes`() {
        val builder = RestClient.builder().withOAuthTokenProtocolSupport()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OAuthClientConfiguration().tokenExchangeTokenResponseClient(
            builder.build(),
            catalogProperties(),
            properties(),
            circulationProperties(),
            notificationProperties(),
            digitalContentProperties(),
        )
        val now = Instant.now()
        val source = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "source-user-token", now, now.plusSeconds(300))
        server.expect(requestTo("https://issuer.example.test/oauth2/token"))
            .andExpect(content().string(containsString("audience=catalog-api")))
            .andExpect(content().string(containsString("scope=catalog.search+catalog.learning-resource.read")))
            .andRespond(
                withSuccess(
                    """{"access_token":"delegated-catalog-token","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","token_type":"Bearer","expires_in":120,"scope":"catalog.search catalog.learning-resource.read"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val registration = registration(
            registrationId = "catalog-service",
            clientId = "web-bff-catalog",
            scopes = arrayOf("catalog.search", "catalog.learning-resource.read"),
        )

        val response = client.getTokenResponse(TokenExchangeGrantRequest(registration, source, null))

        assertThat(response.accessToken.tokenValue).isEqualTo("delegated-catalog-token")
        server.verify()
    }

    @Test
    fun `token exchange authenticates the bff and requests one audience and scope`() {
        val builder = RestClient.builder().withOAuthTokenProtocolSupport()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OAuthClientConfiguration().tokenExchangeTokenResponseClient(
            builder.build(),
            catalogProperties(),
            properties(),
            circulationProperties(),
            notificationProperties(),
            digitalContentProperties(),
        )
        val now = Instant.now()
        val source = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "source-user-token",
            now,
            now.plusSeconds(300),
        )

        server.expect(requestTo("https://issuer.example.test/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", containsString("Basic ")))
            .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(containsString("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange")))
            .andExpect(content().string(containsString("subject_token=source-user-token")))
            .andExpect(content().string(containsString("audience=membership-api")))
            .andExpect(content().string(containsString("scope=membership.profile.read")))
            .andRespond(
                withSuccess(
                    """
                        {
                          "access_token": "delegated-membership-token",
                          "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
                          "token_type": "Bearer",
                          "expires_in": 120,
                          "scope": "membership.profile.read"
                        }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val response = client.getTokenResponse(TokenExchangeGrantRequest(registration(), source, null))

        assertThat(response.accessToken.tokenValue).isEqualTo("delegated-membership-token")
        assertThat(response.accessToken.scopes).containsExactly("membership.profile.read")
        server.verify()
    }

    @Test
    fun `circulation token exchange requests only its audience and self service scopes`() {
        val builder = RestClient.builder().withOAuthTokenProtocolSupport()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OAuthClientConfiguration().tokenExchangeTokenResponseClient(
            builder.build(),
            catalogProperties(),
            properties(),
            circulationProperties(),
            notificationProperties(),
            digitalContentProperties(),
        )
        val now = Instant.now()
        val source = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "source-user-token",
            now,
            now.plusSeconds(300),
        )

        server.expect(requestTo("https://issuer.example.test/oauth2/token"))
            .andExpect(content().string(containsString("audience=circulation-api")))
            .andExpect(content().string(containsString("circulation.eligibility.read")))
            .andExpect(content().string(containsString("circulation.loan.read")))
            .andExpect(content().string(containsString("circulation.loan.request")))
            .andExpect(content().string(containsString("circulation.loan.cancel")))
            .andExpect(content().string(containsString("circulation.loan.renew")))
            .andExpect(content().string(containsString("circulation.reservation.place")))
            .andExpect(content().string(containsString("circulation.reservation.read")))
            .andExpect(content().string(containsString("circulation.reservation.cancel")))
            .andRespond(
                withSuccess(
                    """
                        {
                          "access_token": "delegated-circulation-token",
                          "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
                          "token_type": "Bearer",
                          "expires_in": 120,
                          "scope": "circulation.eligibility.read circulation.loan.read circulation.loan.request circulation.loan.cancel circulation.loan.renew circulation.reservation.read circulation.reservation.place circulation.reservation.cancel"
                        }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val registration = registration(
            registrationId = "circulation-service",
            clientId = "web-bff-circulation",
            scopes = arrayOf(
                "circulation.eligibility.read",
                "circulation.loan.read",
                "circulation.loan.request",
                "circulation.loan.cancel",
                "circulation.loan.renew",
                "circulation.reservation.place",
                "circulation.reservation.read",
                "circulation.reservation.cancel",
            ),
        )
        val response = client.getTokenResponse(TokenExchangeGrantRequest(registration, source, null))

        assertThat(response.accessToken.tokenValue).isEqualTo("delegated-circulation-token")
        assertThat(response.accessToken.scopes)
            .containsExactlyInAnyOrder(
                "circulation.eligibility.read",
                "circulation.loan.read",
                "circulation.loan.request",
                "circulation.loan.cancel",
                "circulation.loan.renew",
                "circulation.reservation.place",
                "circulation.reservation.read",
                "circulation.reservation.cancel",
            )
        server.verify()
    }

    @Test
    fun `digital content token exchange requests its audience and download scopes`() {
        val builder = RestClient.builder().withOAuthTokenProtocolSupport()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OAuthClientConfiguration().tokenExchangeTokenResponseClient(
            builder.build(),
            catalogProperties(),
            properties(),
            circulationProperties(),
            notificationProperties(),
            digitalContentProperties(),
        )
        val now = Instant.now()
        val source = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "source-user-token",
            now,
            now.plusSeconds(300),
        )
        server.expect(requestTo("https://issuer.example.test/oauth2/token"))
            .andExpect(content().string(containsString("audience=digital-content-api")))
            .andExpect(content().string(containsString("digital-content.availability.read")))
            .andExpect(content().string(containsString("digital-content.download.authorize")))
            .andRespond(
                withSuccess(
                    """
                        {
                          "access_token": "delegated-digital-content-token",
                          "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
                          "token_type": "Bearer",
                          "expires_in": 120,
                          "scope": "digital-content.availability.read digital-content.download.authorize"
                        }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        val registration = registration(
            registrationId = "digital-content-service",
            clientId = "web-bff-digital-content",
            scopes = arrayOf(
                "digital-content.availability.read",
                "digital-content.download.authorize",
            ),
        )

        val response = client.getTokenResponse(TokenExchangeGrantRequest(registration, source, null))

        assertThat(response.accessToken.tokenValue).isEqualTo("delegated-digital-content-token")
        server.verify()
    }

    private fun registration(
        registrationId: String = "membership-service",
        clientId: String = "web-bff-membership",
        scopes: Array<String> = arrayOf("membership.profile.read"),
    ): ClientRegistration =
        ClientRegistration.withRegistrationId(registrationId)
            .clientId(clientId)
            .clientSecret("test-only-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .scope(*scopes)
            .tokenUri("https://issuer.example.test/oauth2/token")
            .build()

    private fun properties() = MembershipClientProperties(
        baseUrl = URI("https://membership.internal"),
        audience = "membership-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private fun catalogProperties() = CatalogClientProperties(
        baseUrl = URI("https://catalog.internal"),
        audience = "catalog-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 512 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private fun circulationProperties() = CirculationClientProperties(
        baseUrl = URI("https://circulation.internal"),
        audience = "circulation-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private fun notificationProperties() = NotificationClientProperties(
        baseUrl = URI("https://notification.internal"),
        audience = "notification-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 256 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private fun digitalContentProperties() = DigitalContentClientProperties(
        baseUrl = URI("https://digital-content.internal"),
        audience = "digital-content-api",
        downloadBaseUrl = URI("https://downloads.example.test"),
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
        maximumSignedUrlLifetime = Duration.ofMinutes(5),
    )
}
