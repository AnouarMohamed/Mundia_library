package com.mundiapolis.library.bff.membership

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service

fun interface MembershipProfileUseCase {
    fun profile(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): MemberProfileView
}

@Service
class MembershipProfileService(
    private val authorizer: DelegatedClientAuthorizer,
    private val membershipClient: MembershipClient,
) : MembershipProfileUseCase {
    override fun profile(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): MemberProfileView {
        val authorizedClient = authorizer.authorizeMembership(authentication, request, response)
        return try {
            membershipClient.ownProfile(authorizedClient)
        } catch (failure: MembershipAuthorizationRejectedException) {
            authorizer.invalidateMembership(authentication, request, response)
            throw failure
        }
    }
}
