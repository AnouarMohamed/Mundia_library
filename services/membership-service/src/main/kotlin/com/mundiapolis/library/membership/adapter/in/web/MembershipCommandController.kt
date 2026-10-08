package com.mundiapolis.library.membership.adapter.`in`.web

import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.ChangeAccountStatusCommand
import com.mundiapolis.library.membership.dto.InvalidMembershipCommandException
import com.mundiapolis.library.membership.dto.MembershipCommandExecution
import com.mundiapolis.library.membership.dto.LegacyMembershipImportCommand
import com.mundiapolis.library.membership.dto.LegacyMembershipImportItem
import com.mundiapolis.library.membership.dto.LegacyMembershipImportResult
import com.mundiapolis.library.membership.dto.MembershipRole
import com.mundiapolis.library.membership.service.LegacyMembershipImportService
import com.mundiapolis.library.membership.service.MembershipCommandService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/members")
class MembershipCommandController(
    private val commandService: MembershipCommandService,
    private val principalResolver: MembershipCommandPrincipalResolver,
    private val legacyImports: LegacyMembershipImportService,
) {
    @PostMapping("/{memberId}/status")
    @PreAuthorize("hasAuthority('SCOPE_membership.status.manage')")
    fun changeAccountStatus(
        authentication: JwtAuthenticationToken,
        @PathVariable memberId: UUID,
        @RequestHeader(IF_MATCH_HEADER) ifMatch: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: ChangeAccountStatusRequest,
    ): ResponseEntity<MembershipCommandResponse> {
        val execution = commandService.changeAccountStatus(
            ChangeAccountStatusCommand(
                memberId = memberId,
                expectedVersion = parseIfMatch(ifMatch),
                status = request.status,
                reason = request.reason,
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
                actorMemberId = principalResolver.membershipId(authentication),
            ),
        )
        return ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .eTag("\"${execution.result.aggregateVersion}\"")
            .body(MembershipCommandResponse.from(execution))
    }

    @PutMapping("/legacy-imports/{importId}")
    @PreAuthorize("hasAuthority('SCOPE_membership.import')")
    fun importLegacyMembership(
        authentication: JwtAuthenticationToken,
        @PathVariable importId: UUID,
        @RequestBody request: LegacyMembershipImportRequest,
    ): ResponseEntity<LegacyMembershipImportResult> {
        val result = legacyImports.importBatch(
            LegacyMembershipImportCommand(
                importId = importId,
                sourceRevision = request.sourceRevision,
                items = request.items.map { item ->
                    LegacyMembershipImportItem(
                        item.memberId,
                        item.email,
                        item.fullName,
                        item.universityId,
                        item.status,
                        item.role,
                        item.maxActiveLoans,
                        item.currentActiveLoans,
                        item.hasUnpaidOverdueFines,
                        item.createdAt,
                        item.updatedAt,
                        item.evidenceReferenceSha256,
                        item.contentSha256,
                    )
                },
                actorFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        )
        return ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, result.replayed.toString())
            .body(result)
    }

    @GetMapping("/legacy-imports/{importId}")
    @PreAuthorize("hasAuthority('SCOPE_membership.import')")
    fun legacyMembershipImport(@PathVariable importId: UUID): LegacyMembershipImportResult =
        legacyImports.importEvidence(importId)

    private fun parseIfMatch(value: String): Long {
        val match = VERSION_ETAG.matchEntire(value)
            ?: throw InvalidMembershipCommandException(
                "If-Match must be one quoted aggregate version",
            )
        return match.groupValues[1].toLongOrNull()
            ?: throw InvalidMembershipCommandException("If-Match aggregate version is too large")
    }

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val IF_MATCH_HEADER = "If-Match"
        val VERSION_ETAG = Regex("\"(0|[1-9][0-9]*)\"")
    }
}

data class ChangeAccountStatusRequest(
    val status: AccountStatus,
    val reason: String,
)

data class LegacyMembershipImportRequest(
    val sourceRevision: String,
    val items: List<LegacyMembershipImportItemRequest>,
)

data class LegacyMembershipImportItemRequest(
    val memberId: UUID,
    val email: String,
    val fullName: String,
    val universityId: Int,
    val status: AccountStatus,
    val role: MembershipRole,
    val maxActiveLoans: Int,
    val currentActiveLoans: Int,
    val hasUnpaidOverdueFines: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val evidenceReferenceSha256: String,
    val contentSha256: String,
)

data class MembershipCommandResponse(
    val memberId: UUID,
    val aggregateVersion: Long,
    val status: AccountStatus,
    val occurredAt: Instant,
    val replayed: Boolean,
) {
    companion object {
        fun from(execution: MembershipCommandExecution): MembershipCommandResponse =
            MembershipCommandResponse(
                memberId = execution.result.memberId,
                aggregateVersion = execution.result.aggregateVersion,
                status = execution.result.status,
                occurredAt = execution.result.occurredAt,
                replayed = execution.replayed,
            )
    }
}
