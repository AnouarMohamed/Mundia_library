package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapCommand
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapItem
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapResult
import com.mundiapolis.library.circulation.application.model.InvalidMembershipEligibilityBootstrapException
import com.mundiapolis.library.circulation.application.service.MembershipEligibilityBootstrapService
import com.mundiapolis.library.circulation.domain.model.EligibilityReasonCode
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/circulation/membership-eligibility-bootstrap")
class MembershipEligibilityBootstrapController(
    private val service: MembershipEligibilityBootstrapService,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @PutMapping("/{bootstrapId}")
    @PreAuthorize("hasAuthority('SCOPE_circulation.eligibility.bootstrap')")
    fun bootstrap(
        authentication: JwtAuthenticationToken,
        @PathVariable bootstrapId: UUID,
        @RequestBody request: MembershipEligibilityBootstrapRequest,
    ): ResponseEntity<MembershipEligibilityBootstrapResult> {
        val actor = principalResolver.forAdministrativeCommand(authentication)
        val result = service.bootstrap(
            MembershipEligibilityBootstrapCommand(
                bootstrapId,
                request.sourceRevision,
                request.items.map { item ->
                    MembershipEligibilityBootstrapItem(
                        MemberId(item.memberId),
                        item.status,
                        item.reasonCode?.let(::parseReasonCode),
                        item.sourceVersion,
                        item.sourceOccurredAt,
                        item.contentSha256,
                    )
                },
                actor.idempotencyOwner.fingerprint,
            ),
        )
        return ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, result.replayed.toString())
            .body(result)
    }

    @GetMapping("/{bootstrapId}")
    @PreAuthorize("hasAuthority('SCOPE_circulation.eligibility.bootstrap')")
    fun receipt(@PathVariable bootstrapId: UUID): MembershipEligibilityBootstrapResult =
        service.receipt(bootstrapId)

    private fun parseReasonCode(value: String): EligibilityReasonCode = try {
        EligibilityReasonCode.parse(value)
    } catch (exception: IllegalArgumentException) {
        throw InvalidMembershipEligibilityBootstrapException(
            exception.message ?: "Eligibility reason code is invalid",
        )
    }

    private companion object {
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}

data class MembershipEligibilityBootstrapRequest(
    val sourceRevision: String,
    val items: List<MembershipEligibilityBootstrapItemRequest>,
)

data class MembershipEligibilityBootstrapItemRequest(
    val memberId: UUID,
    val status: MemberEligibilityStatus,
    val reasonCode: String?,
    val sourceVersion: Long,
    val sourceOccurredAt: Instant,
    val contentSha256: String,
)
