package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.MembershipClientProperties
import com.mundiapolis.library.bff.membership.MembershipClient
import com.mundiapolis.library.bff.membership.MembershipProfileService
import com.mundiapolis.library.bff.membership.MembershipReauthenticationRequiredException
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import java.net.URI
import java.time.Duration

class MembershipProfileServiceTest {
    @Test
    fun `downstream authorization rejection evicts the delegated client`() {
        val authorizer = mock(DelegatedClientAuthorizer::class.java)
        val client = mock(MembershipClient::class.java)
        val service = MembershipProfileService(authorizer, client, properties())
        val authentication = mock(OAuth2AuthenticationToken::class.java)
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val authorizedClient = mock(OAuth2AuthorizedClient::class.java)
        `when`(
            authorizer.authorize(
                "membership-service",
                Duration.ofMinutes(5),
                authentication,
                request,
                response,
            ),
        )
            .thenReturn(authorizedClient)
        doThrow(MembershipReauthenticationRequiredException())
            .`when`(client).ownProfile(authorizedClient)

        assertThatThrownBy { service.profile(authentication, request, response) }
            .isInstanceOf(MembershipReauthenticationRequiredException::class.java)

        verify(authorizer).invalidate("membership-service", authentication, request, response)
    }

    private fun properties() = MembershipClientProperties(
        baseUrl = URI("https://membership.internal"),
        audience = "membership-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )
}
