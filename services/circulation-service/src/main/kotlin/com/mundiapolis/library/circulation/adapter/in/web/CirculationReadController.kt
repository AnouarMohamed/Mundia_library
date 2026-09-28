package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.CirculationPolicyView
import com.mundiapolis.library.circulation.application.model.MemberEligibilityView
import com.mundiapolis.library.circulation.application.model.ReservationCommandResult
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationPolicyQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetMemberLoansQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetMemberReservationsQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetMemberEligibilityQuery
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/circulation")
class CirculationReadController(
    private val getPolicy: GetCirculationPolicyQuery,
    private val getMemberEligibility: GetMemberEligibilityQuery,
    private val getMemberLoans: GetMemberLoansQuery,
    private val getMemberReservations: GetMemberReservationsQuery,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @GetMapping("/me/eligibility")
    @PreAuthorize("hasAuthority('SCOPE_circulation.eligibility.read')")
    fun ownEligibility(authentication: JwtAuthenticationToken): MemberEligibilityResponse {
        val principal = principalResolver.forStrictSelf(authentication)
        return MemberEligibilityResponse.from(
            getMemberEligibility.get(requireNotNull(principal.membershipId), principal),
        )
    }

    @GetMapping("/loans/me")
    @PreAuthorize("hasAuthority('SCOPE_circulation.loan.read')")
    fun ownLoans(
        authentication: JwtAuthenticationToken,
        @RequestParam(required = false) status: LoanStatus?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): MemberLoanPageResponse {
        val memberId = requireNotNull(principalResolver.forStrictSelf(authentication).membershipId)
        val page = getMemberLoans.get(memberId, status, limit, cursor)
        return MemberLoanPageResponse(
            page.memberId.value,
            page.items.map(LoanHistoryItemResponse::from),
            page.nextCursor,
        )
    }

    @GetMapping("/reservations/me")
    @PreAuthorize("hasAuthority('SCOPE_circulation.reservation.read')")
    fun ownReservations(
        authentication: JwtAuthenticationToken,
        @RequestParam(required = false) status: ReservationStatus?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): MemberReservationPageResponse {
        val memberId = requireNotNull(principalResolver.forStrictSelf(authentication).membershipId)
        val page = getMemberReservations.get(memberId, status, limit, cursor)
        return MemberReservationPageResponse(
            page.memberId.value,
            page.items.map { ReservationCommandResponse.from(ReservationCommandResult.from(it)) },
            page.nextCursor,
        )
    }

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

data class MemberLoanPageResponse(
    val memberId: UUID,
    val items: List<LoanHistoryItemResponse>,
    val nextCursor: String?,
)

data class LoanHistoryItemResponse(
    val loanId: UUID,
    val memberId: UUID,
    val editionId: UUID,
    val copyId: UUID?,
    val status: LoanStatus,
    val requestedAt: Instant,
    val checkedOutAt: Instant?,
    val dueAt: Instant?,
    val returnedAt: Instant?,
    val rejectedAt: Instant?,
    val renewalCount: Int,
    val version: Long,
) {
    companion object {
        fun from(loan: com.mundiapolis.library.circulation.domain.model.Loan) =
            LoanHistoryItemResponse(
                loan.id.value, loan.memberId.value, loan.editionId.value, loan.copyId?.value,
                loan.status, loan.requestedAt, loan.checkedOutAt, loan.dueAt, loan.returnedAt,
                loan.rejectedAt, loan.renewalCount, loan.version,
            )
    }
}

data class MemberReservationPageResponse(
    val memberId: UUID,
    val items: List<ReservationCommandResponse>,
    val nextCursor: String?,
)

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
