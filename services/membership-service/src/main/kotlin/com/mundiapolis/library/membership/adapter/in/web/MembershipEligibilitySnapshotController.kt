package com.mundiapolis.library.membership.adapter.`in`.web

import com.mundiapolis.library.membership.dto.EligibilitySnapshotPage
import com.mundiapolis.library.membership.dto.EligibilitySnapshotReceipt
import com.mundiapolis.library.membership.service.EligibilitySnapshotService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/members/eligibility-snapshots")
class MembershipEligibilitySnapshotController(
    private val service: EligibilitySnapshotService,
    private val principalResolver: MembershipCommandPrincipalResolver,
) {
    @PutMapping("/{snapshotId}")
    @PreAuthorize("hasAuthority('SCOPE_membership.eligibility.snapshot')")
    fun create(
        authentication: JwtAuthenticationToken,
        @PathVariable snapshotId: UUID,
    ): ResponseEntity<EligibilitySnapshotReceipt> {
        val receipt = service.create(snapshotId, principalResolver.ownerFingerprint(authentication))
        return ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, receipt.replayed.toString())
            .body(receipt)
    }

    @GetMapping("/{snapshotId}")
    @PreAuthorize("hasAuthority('SCOPE_membership.eligibility.snapshot')")
    fun receipt(
        authentication: JwtAuthenticationToken,
        @PathVariable snapshotId: UUID,
    ): EligibilitySnapshotReceipt = service.receipt(
        snapshotId,
        principalResolver.ownerFingerprint(authentication),
    )

    @GetMapping("/{snapshotId}/items")
    @PreAuthorize("hasAuthority('SCOPE_membership.eligibility.snapshot')")
    fun items(
        authentication: JwtAuthenticationToken,
        @PathVariable snapshotId: UUID,
        @RequestParam(required = false) afterMemberId: UUID?,
        @RequestParam(required = false) limit: Int?,
    ): EligibilitySnapshotPage = service.page(
        snapshotId,
        principalResolver.ownerFingerprint(authentication),
        afterMemberId,
        limit,
    )

    private companion object {
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}
