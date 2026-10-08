package com.mundiapolis.library.bff.catalog

import com.mundiapolis.library.bff.config.CatalogClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.CATALOG_ADMIN_REGISTRATION
import com.mundiapolis.library.bff.membership.MembershipAdministrativeAccess
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service
import java.util.UUID

interface CatalogAdminUseCase {
    fun editions(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, query: String?, page: Int?, limit: Int?): CatalogSearchView
    fun work(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID): CatalogWorkView
    fun createWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, key: String, command: CreateCatalogWorkView): CatalogMutationResult
    fun updateWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID, version: Long, key: String, command: UpdateCatalogWorkView): CatalogMutationResult
    fun createEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID, key: String, command: CreateCatalogEditionView): CatalogMutationResult
    fun updateEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionId: UUID, version: Long, key: String, command: UpdateCatalogEditionView): CatalogMutationResult
    fun setActive(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionId: UUID, version: Long, key: String, command: SetCatalogEditionActiveView): CatalogMutationResult
}

@Service
class CatalogAdminService(
    private val membershipAccess: MembershipAdministrativeAccess,
    private val authorizer: DelegatedClientAuthorizer,
    private val client: CatalogClient,
    private val properties: CatalogClientProperties,
) : CatalogAdminUseCase {
    override fun editions(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, query: String?, page: Int?, limit: Int?) =
        withClient(auth, request, response) { client.administrativeEditions(it, query, page, limit) }

    override fun work(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID) =
        withClient(auth, request, response) { client.work(it, workId) }

    override fun createWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, key: String, command: CreateCatalogWorkView) =
        withClient(auth, request, response) { client.createWork(it, command, key) }

    override fun updateWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID, version: Long, key: String, command: UpdateCatalogWorkView) =
        withClient(auth, request, response) { client.updateWork(it, workId, version, command, key) }

    override fun createEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, workId: UUID, key: String, command: CreateCatalogEditionView) =
        withClient(auth, request, response) { client.createEdition(it, workId, command, key) }

    override fun updateEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionId: UUID, version: Long, key: String, command: UpdateCatalogEditionView) =
        withClient(auth, request, response) { client.updateEdition(it, editionId, version, command, key) }

    override fun setActive(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, editionId: UUID, version: Long, key: String, command: SetCatalogEditionActiveView) =
        withClient(auth, request, response) { client.setEditionActive(it, editionId, version, command, key) }

    private fun <T> withClient(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, operation: (OAuth2AuthorizedClient) -> T): T =
        membershipAccess.withVerifiedAdministrator(auth, request, response) {
            val authorized = authorizer.authorize(CATALOG_ADMIN_REGISTRATION, properties.maximumDelegatedTokenLifetime, auth, request, response)
            try {
                operation(authorized)
            } catch (failure: CatalogAuthorizationRejectedException) {
                authorizer.invalidate(CATALOG_ADMIN_REGISTRATION, auth, request, response)
                throw failure
            }
        }
}
