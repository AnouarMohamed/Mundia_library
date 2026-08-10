package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.CirculationPolicyView
import com.mundiapolis.library.circulation.application.model.MemberEligibilityView
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationPolicyQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetMemberEligibilityQuery
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/circulation")
class CirculationReadController(
    private val getPolicy: GetCirculationPolicyQuery,
    private val getMemberEligibility: GetMemberEligibilityQuery,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @GetMapping("/policy")
    @PreAuthorize("hasAuthority('SCOPE_circulation.policy.read')")
    fun policy(): ResponseEntity<CirculationPolicyResponse> {
        val policy = getPolicy.get()
        return ResponseEntity.ok()
            .eTag(policy.revision)
            .body(CirculationPolicyResponse.from(policy))
    }

    @GetMapping("/members/{memberId}/eligibility")
    @PreAuthorize(
        "hasAnyAuthority(" +
            "'SCOPE_circulation.eligibility.read'," +
            "'SCOPE_circulation.eligibility.read.any')",
    )
    fun eligibility(
        authentication: JwtAuthenticationToken,
        @PathVariable memberId: UUID,
    ): MemberEligibilityResponse = MemberEligibilityResponse.from(
        getMemberEligibility.get(
            MemberId(memberId),
            principalResolver.forEligibilityRead(authentication),
        ),
    )
}

data class CirculationPolicyResponse(
    val revision: String,
    val sequence: Long,
    val defaultLoanPeriod: String,
    val renewalPeriod: String,
    val maximumRenewals: Int,
    val fineCurrency: String,
    val reservationHoldPeriod: String,
    val maximumActiveReservations: Int,
    val effectiveAt: Instant,
) {
    companion object {
        fun from(view: CirculationPolicyView): CirculationPolicyResponse =
            CirculationPolicyResponse(
                revision = view.revision,
                sequence = view.sequence,
                defaultLoanPeriod = view.defaultLoanPeriod.toString(),
                renewalPeriod = view.renewalPeriod.toString(),
                maximumRenewals = view.maximumRenewals,
                fineCurrency = view.fineCurrency,
                reservationHoldPeriod = view.reservationHoldPeriod.toString(),
                maximumActiveReservations = view.maximumActiveReservations,
                effectiveAt = view.effectiveAt,
            )
    }
}

data class MemberEligibilityResponse(
    val memberId: UUID,
    val status: MemberEligibilityStatus,
    val reasonCode: String?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
) {
    companion object {
        fun from(view: MemberEligibilityView): MemberEligibilityResponse =
            MemberEligibilityResponse(
                memberId = view.memberId.value,
                status = view.status,
                reasonCode = view.reasonCode,
                sourceVersion = view.sourceVersion,
                sourceOccurredAt = view.sourceOccurredAt,
            )
    }
}
