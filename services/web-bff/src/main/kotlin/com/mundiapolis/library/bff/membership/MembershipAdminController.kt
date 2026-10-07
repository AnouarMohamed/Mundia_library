package com.mundiapolis.library.bff.membership

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/members")
class MembershipAdminController(private val membership: MembershipAdminUseCase) {
    @GetMapping("")
    fun members(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(defaultValue = "PENDING") status: AccountStatusView,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<AdminMemberPageView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(membership.members(authentication, request, response, status, limit, cursor))

    @PostMapping("/{memberId}/status")
    fun changeStatus(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable memberId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody command: ChangeMemberStatusView,
    ): ResponseEntity<MembershipCommandView> {
        val expectedVersion = VERSION_ETAG.matchEntire(ifMatch)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw MembershipInvalidRequestException()
        val result = membership.changeStatus(authentication, request, response, memberId, expectedVersion, idempotencyKey, command)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag("\"${result.command.aggregateVersion}\"")
            .header("Idempotency-Replayed", result.idempotencyReplayed.toString())
            .body(result.command)
    }

    private companion object {
        val VERSION_ETAG = Regex("\"(0|[1-9][0-9]*)\"")
    }
}
