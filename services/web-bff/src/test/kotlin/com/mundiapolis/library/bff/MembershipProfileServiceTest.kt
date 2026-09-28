package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.membership.DelegatedClientAuthorizer
import com.mundiapolis.library.bff.membership.MembershipClient
import com.mundiapolis.library.bff.membership.MembershipProfileService
import com.mundiapolis.library.bff.membership.MembershipReauthenticationRequiredException
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

class MembershipProfileServiceTest {
    @Test
    fun `downstream authorization rejection evicts the delegated client`() {
        val authorizer = mock(DelegatedClientAuthorizer::class.java)
        val client = mock(MembershipClient::class.java)
        val service = MembershipProfileService(authorizer, client)
        val authentication = mock(OAuth2AuthenticationToken::class.java)
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val authorizedClient = mock(OAuth2AuthorizedClient::class.java)
        `when`(authorizer.authorizeMembership(authentication, request, response))
            .thenReturn(authorizedClient)
        doThrow(MembershipReauthenticationRequiredException())
            .`when`(client).ownProfile(authorizedClient)

        assertThatThrownBy { service.profile(authentication, request, response) }
            .isInstanceOf(MembershipReauthenticationRequiredException::class.java)

        verify(authorizer).invalidateMembership(authentication, request, response)
    }
}
