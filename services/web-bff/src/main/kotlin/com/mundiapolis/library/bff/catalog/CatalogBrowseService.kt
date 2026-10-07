package com.mundiapolis.library.bff.catalog

import com.mundiapolis.library.bff.config.CatalogClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.CATALOG_REGISTRATION
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service
import java.util.UUID

interface CatalogBrowseUseCase {
    fun search(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, criteria: CatalogSearchCriteria): CatalogSearchView
    fun learningResources(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, criteria: LearningResourceSearchCriteria): LearningResourcePageView
    fun learningResource(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, resourceId: UUID): LearningResourceView
    fun learningResourceCategories(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse): List<String>
    fun editions(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionIds: List<UUID>): List<CatalogEditionView>
}

@Service
class CatalogBrowseService(
    private val authorizer: DelegatedClientAuthorizer,
    private val client: CatalogClient,
    private val properties: CatalogClientProperties,
) : CatalogBrowseUseCase {
    override fun search(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, criteria: CatalogSearchCriteria) =
        withClient(authentication, request, response) { client.search(it, criteria) }

    override fun learningResources(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, criteria: LearningResourceSearchCriteria) =
        withClient(authentication, request, response) { client.learningResources(it, criteria) }

    override fun learningResource(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, resourceId: UUID) =
        withClient(authentication, request, response) { client.learningResource(it, resourceId) }

    override fun learningResourceCategories(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse) =
        withClient(authentication, request, response) { client.learningResourceCategories(it) }

    override fun editions(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionIds: List<UUID>): List<CatalogEditionView> {
        require(editionIds.distinct().size == editionIds.size) { "editionId values must be unique" }
        return withClient(authentication, request, response) { client.editions(it, editionIds) }
    }

    private fun <T> withClient(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        operation: (OAuth2AuthorizedClient) -> T,
    ): T {
        val authorizedClient = authorizer.authorize(
            CATALOG_REGISTRATION,
            properties.maximumDelegatedTokenLifetime,
            authentication,
            request,
            response,
        )
        return try {
            operation(authorizedClient)
        } catch (failure: CatalogAuthorizationRejectedException) {
            authorizer.invalidate(CATALOG_REGISTRATION, authentication, request, response)
            throw failure
        }
    }
}
