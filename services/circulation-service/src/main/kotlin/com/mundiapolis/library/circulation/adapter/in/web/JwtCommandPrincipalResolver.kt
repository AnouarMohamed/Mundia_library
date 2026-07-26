package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.InvalidAuthenticationClaimException
import com.mundiapolis.library.circulation.application.model.MissingMembershipClaimException
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JwtCommandPrincipalResolver {
    fun forRequest(authentication: JwtAuthenticationToken): CommandPrincipal {
        return forMemberCommand(authentication, REQUEST_ON_BEHALF_AUTHORITY)
    }

    fun forRenewal(authentication: JwtAuthenticationToken): CommandPrincipal =
        forMemberCommand(authentication, RENEW_ON_BEHALF_AUTHORITY)

    private fun forMemberCommand(
        authentication: JwtAuthenticationToken,
        onBehalfAuthority: String,
    ): CommandPrincipal {
        val canActOnBehalf = authentication.authorities
            .any { it.authority == onBehalfAuthority }
        val membershipId = if (canActOnBehalf) {
            null
        } else {
            requiredMembershipId(authentication.token)
        }

        return resolve(authentication.token, membershipId, canActOnBehalf)
    }

    fun forAdministrativeCommand(authentication: JwtAuthenticationToken): CommandPrincipal =
        resolve(authentication.token, membershipId = null, canActOnBehalf = false)

    private fun resolve(
        jwt: Jwt,
        membershipId: MemberId?,
        canActOnBehalf: Boolean,
    ): CommandPrincipal {
        val issuer = jwt.issuer?.toString()
            ?: throw InvalidAuthenticationClaimException("iss")
        return CommandPrincipal(
            idempotencyOwner = IdempotencyOwner.fromIdentity(
                issuer = issuer,
                subject = optionalStringClaim(jwt, "sub"),
                authorizedParty = optionalStringClaim(jwt, "azp"),
                clientId = optionalStringClaim(jwt, "client_id"),
            ),
            membershipId = membershipId,
            canActOnBehalf = canActOnBehalf,
        )
    }

    private fun requiredMembershipId(jwt: Jwt): MemberId {
        val rawMembershipId = optionalStringClaim(jwt, MEMBERSHIP_ID_CLAIM)
            ?: throw MissingMembershipClaimException()
        val parsed = try {
            UUID.fromString(rawMembershipId)
        } catch (_: IllegalArgumentException) {
            throw InvalidAuthenticationClaimException(MEMBERSHIP_ID_CLAIM)
        }
        if (!parsed.toString().equals(rawMembershipId, ignoreCase = true)) {
            throw InvalidAuthenticationClaimException(MEMBERSHIP_ID_CLAIM)
        }
        return MemberId(parsed)
    }

    private fun optionalStringClaim(jwt: Jwt, claim: String): String? {
        val value = jwt.claims[claim] ?: return null
        if (value !is String || value.isBlank() || value.any(Char::isISOControl)) {
            throw InvalidAuthenticationClaimException(claim)
        }
        return value
    }

    private companion object {
        const val MEMBERSHIP_ID_CLAIM = "membership_id"
        const val REQUEST_ON_BEHALF_AUTHORITY = "SCOPE_circulation.loan.request.on-behalf"
        const val RENEW_ON_BEHALF_AUTHORITY = "SCOPE_circulation.loan.renew.on-behalf"
    }
}
