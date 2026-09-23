package com.mundiapolis.library.membership.adapter.`in`.web

import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import com.mundiapolis.library.membership.service.MembershipService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/members")
class MembershipReadController(
    private val membershipService: MembershipService,
) {
    @GetMapping("/{memberId}/profile")
    @PreAuthorize(
        "hasAnyAuthority(" +
            "'SCOPE_membership.profile.read'," +
            "'SCOPE_membership.profile.read.any')",
    )
    fun profile(
        authentication: JwtAuthenticationToken,
        @PathVariable memberId: UUID,
    ): MemberProfile {
        requireSelfOrDelegated(authentication, memberId, "SCOPE_membership.profile.read.any")
        return membershipService.getMemberProfile(memberId.toString())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found")
    }

    @GetMapping("/{memberId}/eligibility")
    @PreAuthorize(
        "hasAnyAuthority(" +
            "'SCOPE_membership.eligibility.read'," +
            "'SCOPE_membership.eligibility.read.any')",
    )
    fun eligibility(
        authentication: JwtAuthenticationToken,
        @PathVariable memberId: UUID,
    ): MemberEligibility {
        requireSelfOrDelegated(authentication, memberId, "SCOPE_membership.eligibility.read.any")
        return membershipService.checkEligibility(memberId.toString())
    }

    @GetMapping("/{memberId}/identity-evidence")
    @PreAuthorize("hasAuthority('SCOPE_membership.identity-evidence.read')")
    fun identityEvidence(
        @PathVariable memberId: UUID,
    ): IdentityEvidenceRef = membershipService.getIdentityEvidenceRef(memberId.toString())
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Identity evidence not found")

    private fun requireSelfOrDelegated(
        authentication: JwtAuthenticationToken,
        memberId: UUID,
        delegatedAuthority: String,
    ) {
        if (authentication.authorities.any { it.authority == delegatedAuthority }) {
            return
        }
        val claim = authentication.token.getClaimAsString("membership_id")
        if (claim == null || runCatching { UUID.fromString(claim) }.getOrNull() != memberId) {
            throw AccessDeniedException("The membership_id claim does not match the requested member")
        }
    }
}
