package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.catalog.CatalogBrowseService
import com.mundiapolis.library.bff.catalog.CatalogClient
import com.mundiapolis.library.bff.catalog.CatalogReauthenticationRequiredException
import com.mundiapolis.library.bff.catalog.LearningResourceSearchCriteria
import com.mundiapolis.library.bff.config.CatalogClientProperties
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

class CatalogBrowseServiceTest {
    @Test
    fun `downstream authorization rejection evicts the delegated catalog token`() {
        val authorizer = mock(DelegatedClientAuthorizer::class.java)
        val client = mock(CatalogClient::class.java)
        val service = CatalogBrowseService(authorizer, client, properties())
        val authentication = mock(OAuth2AuthenticationToken::class.java)
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val authorizedClient = mock(OAuth2AuthorizedClient::class.java)
        val criteria = LearningResourceSearchCriteria(null, null, null, null)
        `when`(authorizer.authorize("catalog-service", Duration.ofMinutes(5), authentication, request, response))
            .thenReturn(authorizedClient)
        doThrow(CatalogReauthenticationRequiredException())
            .`when`(client).learningResources(authorizedClient, criteria)

        assertThatThrownBy { service.learningResources(authentication, request, response, criteria) }
            .isInstanceOf(CatalogReauthenticationRequiredException::class.java)

        verify(authorizer).invalidate("catalog-service", authentication, request, response)
    }

    private fun properties() = CatalogClientProperties(
        baseUrl = URI("https://catalog.internal"),
        audience = "catalog-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(3),
        maximumResponseBytes = 512 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )
}
