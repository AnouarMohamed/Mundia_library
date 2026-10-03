package com.mundiapolis.library.notification.adapter.`in`.web

import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationPreference
import com.mundiapolis.library.notification.dto.NotificationReadStatus
import com.mundiapolis.library.notification.dto.EmailSuppressionRemoval
import com.mundiapolis.library.notification.dto.RemoveEmailSuppressionRequest
import com.mundiapolis.library.notification.dto.UpdateNotificationPreferenceRequest
import com.mundiapolis.library.notification.service.EmailSuppressionService
import com.mundiapolis.library.notification.service.NotificationPreferencePreconditionRequiredException
import com.mundiapolis.library.notification.service.NotificationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val service: NotificationService,
    private val emailSuppressionService: EmailSuppressionService,
) {
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

    @GetMapping("/preferences/me")
    @PreAuthorize("hasAuthority('SCOPE_notification.preferences.read')")
    fun getPreference(
        authentication: JwtAuthenticationToken,
    ): ResponseEntity<NotificationPreference> {
        val preference = service.getPreference(requiredMemberId(authentication))
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(preference.strongEtag())
            .body(preference)
    }

    @PutMapping("/preferences/me")
    @PreAuthorize("hasAuthority('SCOPE_notification.preferences.write')")
    fun updatePreference(
        authentication: JwtAuthenticationToken,
        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: UpdateNotificationPreferenceRequest,
    ): ResponseEntity<NotificationPreference> {
        val expectedVersion = parsePreferenceVersion(ifMatch)
        val preference = service.updatePreference(requiredMemberId(authentication), expectedVersion, request)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(preference.strongEtag())
            .body(preference)
    }

    @PostMapping("/email-suppressions/{memberId}/removal")
    @PreAuthorize("hasAuthority('SCOPE_notification.suppression.write')")
    fun removeEmailSuppression(
        authentication: JwtAuthenticationToken,
        @PathVariable memberId: UUID,
        @RequestHeader(name = "Idempotency-Key") requestId: UUID,
        @Valid @RequestBody request: RemoveEmailSuppressionRequest,
    ): ResponseEntity<EmailSuppressionRemoval> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            emailSuppressionService.remove(
                requestId,
                memberId,
                requiredActorSubject(authentication),
                request.justification,
            ),
        )

    private fun requiredMemberId(authentication: JwtAuthenticationToken): UUID {
        val claim = authentication.token.getClaimAsString("membership_id")
            ?: throw AccessDeniedException("The membership_id claim is required")
        val id = runCatching { UUID.fromString(claim) }.getOrNull()
        if (id == null || id.toString() != claim) {
            throw AccessDeniedException("The membership_id claim must be a canonical UUID")
        }
        return id
    }

    private fun parsePreferenceVersion(ifMatch: String?): Long {
        if (ifMatch == null) throw NotificationPreferencePreconditionRequiredException()
        val raw = STRONG_ETAG.matchEntire(ifMatch)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("If-Match must contain one strong preference version")
        return raw.toLongOrNull() ?: throw IllegalArgumentException("If-Match preference version is invalid")
    }

    private fun requiredActorSubject(authentication: JwtAuthenticationToken): String =
        authentication.token.subject ?: throw AccessDeniedException("The sub claim is required")

    private fun NotificationPreference.strongEtag(): String = "\"$version\""

    private companion object {
        val STRONG_ETAG = Regex("\"(0|[1-9][0-9]{0,18})\"")
    }
}
