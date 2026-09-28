package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.circulation.CirculationClient
import com.mundiapolis.library.bff.circulation.CirculationReauthenticationRequiredException
import com.mundiapolis.library.bff.circulation.CirculationService
import com.mundiapolis.library.bff.circulation.RequestLoanView
import com.mundiapolis.library.bff.config.CirculationClientProperties
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
import java.util.UUID

class CirculationServiceTest {
    @Test
    fun `downstream authorization rejection evicts only the circulation client`() {
        val authorizer = mock(DelegatedClientAuthorizer::class.java)
        val client = mock(CirculationClient::class.java)
        val service = CirculationService(authorizer, client, properties())
        val authentication = mock(OAuth2AuthenticationToken::class.java)
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val authorizedClient = mock(OAuth2AuthorizedClient::class.java)
        `when`(
            authorizer.authorize(
                "circulation-service",
                Duration.ofMinutes(5),
                authentication,
                request,
                response,
            ),
        ).thenReturn(authorizedClient)
        val command = RequestLoanView(UUID.randomUUID())
        doThrow(CirculationReauthenticationRequiredException())
            .`when`(client).requestOwnLoan(authorizedClient, command, IDEMPOTENCY_KEY)

        assertThatThrownBy {
            service.requestLoan(authentication, request, response, command, IDEMPOTENCY_KEY)
        }.isInstanceOf(CirculationReauthenticationRequiredException::class.java)

        verify(authorizer).invalidate("circulation-service", authentication, request, response)
    }

    private fun properties() = CirculationClientProperties(
        baseUrl = URI("https://circulation.internal"),
        audience = "circulation-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(5),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private companion object {
        const val IDEMPOTENCY_KEY = "loan-request-00000001"
    }
}
