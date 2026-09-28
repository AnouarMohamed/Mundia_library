package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.MembershipClientProperties
import com.mundiapolis.library.bff.membership.MembershipClient
import com.mundiapolis.library.bff.membership.MembershipDelegationRejectedException
import com.mundiapolis.library.bff.membership.MembershipProtocolException
import com.mundiapolis.library.bff.membership.MembershipReauthenticationRequiredException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.time.Duration
import java.time.Instant

class MembershipClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: MembershipClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl("https://membership.internal")
        server = MockRestServiceServer.bindTo(builder).build()
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .findAndAddModules()
            .build()
        client = MembershipClient(builder.build(), mapper, properties())
    }

    @Test
    fun `profile uses only the fixed me route and delegated bearer token`() {
        server.expect(requestTo("https://membership.internal/api/v1/members/me/profile"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andRespond(withSuccess(VALID_PROFILE, MediaType.APPLICATION_JSON))

        val profile = client.ownProfile(authorizedClient())

        assertThat(profile.memberId.toString()).isEqualTo("10000000-0000-0000-0000-000000000001")
        assertThat(profile.fullName).isEqualTo("Library Member")
        server.verify()
    }

    @Test
    fun `downstream unauthorized response requires reauthentication`() {
        server.expect(requestTo("https://membership.internal/api/v1/members/me/profile"))
            .andRespond(withUnauthorizedRequest())

        assertThatThrownBy { client.ownProfile(authorizedClient()) }
            .isInstanceOf(MembershipReauthenticationRequiredException::class.java)
        server.verify()
    }

    @Test
    fun `downstream forbidden response remains distinct from expired authorization`() {
        server.expect(requestTo("https://membership.internal/api/v1/members/me/profile"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN))

        assertThatThrownBy { client.ownProfile(authorizedClient()) }
            .isInstanceOf(MembershipDelegationRejectedException::class.java)
        server.verify()
    }

    @Test
    fun `malformed profile data is rejected`() {
        server.expect(requestTo("https://membership.internal/api/v1/members/me/profile"))
            .andRespond(
                withSuccess(
                    VALID_PROFILE.replace("\"universityId\": 90000001", "\"universityId\": 0"),
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThatThrownBy { client.ownProfile(authorizedClient()) }
            .isInstanceOf(MembershipProtocolException::class.java)
        server.verify()
    }

    private fun authorizedClient(): OAuth2AuthorizedClient {
        val registration = ClientRegistration.withRegistrationId("membership-service")
            .clientId("web-bff-membership")
            .clientSecret("test-only-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .tokenUri("https://issuer.example.test/oauth2/token")
            .build()
        val now = Instant.now()
        val token = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "delegated-token",
            now,
            now.plusSeconds(120),
        )
        return OAuth2AuthorizedClient(registration, "member", token)
    }

    private fun properties() = MembershipClientProperties(
        baseUrl = URI("https://membership.internal"),
        audience = "membership-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private companion object {
        val VALID_PROFILE = """
            {
              "memberId": "10000000-0000-0000-0000-000000000001",
              "email": "member@example.test",
              "fullName": "Library Member",
              "universityId": 90000001,
              "status": "APPROVED",
              "role": "USER",
              "createdAt": "2026-01-01T00:00:00Z",
              "updatedAt": "2026-09-01T00:00:00Z"
            }
        """.trimIndent()
    }
}
