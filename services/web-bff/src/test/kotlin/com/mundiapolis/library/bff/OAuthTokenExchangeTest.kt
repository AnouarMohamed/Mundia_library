package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.MembershipClientProperties
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
    fun `token exchange authenticates the bff and requests one audience and scope`() {
        val builder = RestClient.builder().withOAuthTokenProtocolSupport()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OAuthClientConfiguration().tokenExchangeTokenResponseClient(
            builder.build(),
            properties(),
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

    private fun registration(): ClientRegistration =
        ClientRegistration.withRegistrationId("membership-service")
            .clientId("web-bff-membership")
            .clientSecret("test-only-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .scope("membership.profile.read")
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
}
