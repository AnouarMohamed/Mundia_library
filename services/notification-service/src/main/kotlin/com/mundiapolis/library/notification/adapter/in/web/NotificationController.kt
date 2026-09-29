package com.mundiapolis.library.notification.adapter.`in`.web

import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationReadStatus
import com.mundiapolis.library.notification.service.NotificationService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val service: NotificationService) {
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('SCOPE_notification.inbox.read')")
    fun list(
        authentication: JwtAuthenticationToken,
        @RequestParam(defaultValue = "ALL") status: NotificationReadStatus,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
        @RequestParam(required = false) @Size(max = 128) cursor: String?,
    ): ResponseEntity<NotificationPage> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.list(requiredMemberId(authentication), status, limit, cursor))

    @PatchMapping("/{notificationId}/read")
    @PreAuthorize("hasAuthority('SCOPE_notification.inbox.write')")
    fun markRead(
        authentication: JwtAuthenticationToken,
        @PathVariable notificationId: UUID,
    ): ResponseEntity<NotificationItem> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.markRead(requiredMemberId(authentication), notificationId))

    private fun requiredMemberId(authentication: JwtAuthenticationToken): UUID {
        val claim = authentication.token.getClaimAsString("membership_id")
            ?: throw AccessDeniedException("The membership_id claim is required")
        val id = runCatching { UUID.fromString(claim) }.getOrNull()
        if (id == null || id.toString() != claim) {
            throw AccessDeniedException("The membership_id claim must be a canonical UUID")
        }
        return id
    }
}
