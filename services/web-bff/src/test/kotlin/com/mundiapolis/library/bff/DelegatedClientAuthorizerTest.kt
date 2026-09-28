package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.MembershipClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration
import com.mundiapolis.library.bff.membership.DelegatedClientAuthorizer
import com.mundiapolis.library.bff.membership.MembershipDelegationProtocolException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import java.net.URI
import java.time.Duration
import java.time.Instant

class DelegatedClientAuthorizerTest {
    @Test
    fun `membership authorization exchanges the current user access token`() {
        val source = authorizedClient("institutional", AuthorizationGrantType.AUTHORIZATION_CODE, 300)
        val delegated = authorizedClient("membership-service", AuthorizationGrantType.TOKEN_EXCHANGE, 120)
        val manager = RecordingManager(source, delegated)
        val repository = mock(OAuth2AuthorizedClientRepository::class.java)
        val authorizer = DelegatedClientAuthorizer(manager, repository, properties())
        val authentication = authentication()
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = authorizer.authorizeMembership(authentication, request, response)

        assertThat(result).isSameAs(delegated)
        assertThat(manager.requests.map(OAuth2AuthorizeRequest::getClientRegistrationId))
            .containsExactly("institutional", "membership-service")
        val subjectToken = manager.requests[1]
            .getAttribute<OAuth2AccessToken>(OAuthClientConfiguration.SUBJECT_TOKEN_ATTRIBUTE)
        assertThat(subjectToken).isSameAs(source.accessToken)
        assertThat(manager.requests[1].attributes["jakarta.servlet.http.HttpServletRequest"])
            .isSameAs(request)
    }

    @Test
    fun `delegated tokens exceeding the bounded lifetime are rejected`() {
        val source = authorizedClient("institutional", AuthorizationGrantType.AUTHORIZATION_CODE, 300)
        val delegated = authorizedClient("membership-service", AuthorizationGrantType.TOKEN_EXCHANGE, 601)
        val authorizer = DelegatedClientAuthorizer(
            RecordingManager(source, delegated),
            mock(OAuth2AuthorizedClientRepository::class.java),
            properties(),
        )

        assertThatThrownBy {
            authorizer.authorizeMembership(
                authentication(),
                MockHttpServletRequest(),
                MockHttpServletResponse(),
            )
        }.isInstanceOf(MembershipDelegationProtocolException::class.java)
    }

    @Test
    fun `invalidating membership removes only the delegated authorized client`() {
        val repository = mock(OAuth2AuthorizedClientRepository::class.java)
        val authorizer = DelegatedClientAuthorizer(
            RecordingManager(null, null),
            repository,
            properties(),
        )
        val authentication = authentication()
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        authorizer.invalidateMembership(authentication, request, response)

        verify(repository).removeAuthorizedClient(
            "membership-service",
            authentication,
            request,
            response,
        )
    }

    private fun authentication(): OAuth2AuthenticationToken {
        val now = Instant.now()
        val idToken = OidcIdToken(
            "id-token",
            now,
            now.plusSeconds(300),
            mapOf("sub" to "member-subject"),
        )
        return OAuth2AuthenticationToken(
            DefaultOidcUser(emptyList(), idToken),
            emptyList(),
            "institutional",
        )
    }

    private fun authorizedClient(
        registrationId: String,
        grantType: AuthorizationGrantType,
        lifetimeSeconds: Long,
    ): OAuth2AuthorizedClient {
        val registration = ClientRegistration.withRegistrationId(registrationId)
            .clientId("$registrationId-client")
            .clientSecret("test-only-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(grantType)
            .tokenUri("https://issuer.example.test/oauth2/token")
            .apply {
                if (grantType == AuthorizationGrantType.AUTHORIZATION_CODE) {
                    authorizationUri("https://issuer.example.test/oauth2/authorize")
                    redirectUri("https://bff.example.test/login/oauth2/code/institutional")
                }
            }
            .build()
        val now = Instant.now()
        val token = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "$registrationId-token",
            now,
            now.plusSeconds(lifetimeSeconds),
        )
        return OAuth2AuthorizedClient(registration, "member-subject", token)
    }

    private fun properties() = MembershipClientProperties(
        baseUrl = URI("https://membership.internal"),
        audience = "membership-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private class RecordingManager(
        private val source: OAuth2AuthorizedClient?,
        private val delegated: OAuth2AuthorizedClient?,
    ) : OAuth2AuthorizedClientManager {
        val requests = mutableListOf<OAuth2AuthorizeRequest>()

        override fun authorize(authorizeRequest: OAuth2AuthorizeRequest): OAuth2AuthorizedClient? {
            requests += authorizeRequest
            return when (authorizeRequest.clientRegistrationId) {
                "institutional" -> source
                "membership-service" -> delegated
                else -> null
            }
        }
    }
}
