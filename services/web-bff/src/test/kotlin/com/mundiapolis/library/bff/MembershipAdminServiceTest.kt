package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.MembershipClientProperties
import com.mundiapolis.library.bff.membership.AccountStatusView
import com.mundiapolis.library.bff.membership.MemberProfileView
import com.mundiapolis.library.bff.membership.MembershipAdminService
import com.mundiapolis.library.bff.membership.MembershipClient
import com.mundiapolis.library.bff.membership.MembershipDelegationRejectedException
import com.mundiapolis.library.bff.membership.MembershipRoleView
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

class MembershipAdminServiceTest {
    @Test
    fun `administration rechecks authoritative approved role and evicts rejected delegation`() {
        val authorizer = mock(DelegatedClientAuthorizer::class.java)
        val client = mock(MembershipClient::class.java)
        val service = MembershipAdminService(authorizer, client, properties())
        val authentication = mock(OAuth2AuthenticationToken::class.java)
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val authorizedClient = mock(OAuth2AuthorizedClient::class.java)
        `when`(
            authorizer.authorize(
                "membership-admin-service",
                Duration.ofMinutes(5),
                authentication,
                request,
                response,
            ),
        ).thenReturn(authorizedClient)
        `when`(client.ownProfile(authorizedClient)).thenReturn(
            MemberProfileView(
                MEMBER_ID,
                "member@example.test",
                "Library Member",
                90_000_001,
                AccountStatusView.APPROVED,
                MembershipRoleView.USER,
                NOW,
                NOW,
            ),
        )

        assertThatThrownBy {
            service.members(authentication, request, response, AccountStatusView.PENDING, 25, null)
        }.isInstanceOf(MembershipDelegationRejectedException::class.java)

        verify(authorizer).invalidate("membership-admin-service", authentication, request, response)
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
        val MEMBER_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val NOW: Instant = Instant.parse("2026-10-07T10:00:00Z")
    }
}
