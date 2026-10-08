package com.mundiapolis.library.bff.membership

import com.mundiapolis.library.bff.config.MembershipClientProperties
import com.mundiapolis.library.bff.config.OAuthClientConfiguration.Companion.MEMBERSHIP_ADMIN_REGISTRATION
import com.mundiapolis.library.bff.security.DelegatedClientAuthorizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service
import java.util.UUID

interface MembershipAdminUseCase {
    fun members(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, status: AccountStatusView, limit: Int?, cursor: String?): AdminMemberPageView
    fun changeStatus(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, memberId: UUID, expectedVersion: Long, idempotencyKey: String, command: ChangeMemberStatusView): MembershipMutationResult
}

@Service
class MembershipAdminService(
    private val access: MembershipAdministrativeAccess,
    private val client: MembershipClient,
) : MembershipAdminUseCase {
    override fun members(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, status: AccountStatusView, limit: Int?, cursor: String?): AdminMemberPageView =
        access.withVerifiedAdministrator(authentication, request, response) { client.membersForAdministration(it, status, limit, cursor) }

    override fun changeStatus(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, memberId: UUID, expectedVersion: Long, idempotencyKey: String, command: ChangeMemberStatusView): MembershipMutationResult =
        access.withVerifiedAdministrator(authentication, request, response) {
            client.changeMemberStatus(it, memberId, expectedVersion, idempotencyKey, command)
        }
}

@Service
class MembershipAdministrativeAccess(
    private val authorizer: DelegatedClientAuthorizer,
    private val client: MembershipClient,
    private val properties: MembershipClientProperties,
) {
    fun <T> withVerifiedAdministrator(authentication: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, operation: (OAuth2AuthorizedClient) -> T): T {
        val authorizedClient = authorizer.authorize(MEMBERSHIP_ADMIN_REGISTRATION, properties.maximumDelegatedTokenLifetime, authentication, request, response)
        return try {
            val operator = client.ownProfile(authorizedClient)
            if (operator.status != AccountStatusView.APPROVED ||
                operator.role !in setOf(MembershipRoleView.ADMIN, MembershipRoleView.SUPER_ADMIN)) {
                throw MembershipDelegationRejectedException()
            }
            operation(authorizedClient)
        } catch (failure: MembershipAuthorizationRejectedException) {
            authorizer.invalidate(MEMBERSHIP_ADMIN_REGISTRATION, authentication, request, response)
            throw failure
        }
    }
}
