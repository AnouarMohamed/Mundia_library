package com.mundiapolis.library.bff.notification

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notifications: NotificationSelfServiceUseCase,
) {
    @GetMapping
    fun notifications(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) status: NotificationReadStatusView?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<NotificationPageView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(notifications.notifications(authentication, request, response, status, limit, cursor))

    @PatchMapping("/{notificationId}/read")
    fun markRead(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable notificationId: UUID,
    ): ResponseEntity<NotificationItemView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(notifications.markRead(authentication, request, response, notificationId))

    @GetMapping("/preferences")
    fun preference(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<NotificationPreferenceView> = preferenceResponse(
        notifications.preference(authentication, request, response),
    )

    @PutMapping("/preferences")
    fun updatePreference(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) entityTag: String?,
        @RequestBody update: UpdateNotificationPreferenceView,
    ): ResponseEntity<NotificationPreferenceView> = preferenceResponse(
        notifications.updatePreference(
            authentication,
            request,
            response,
            entityTag ?: throw NotificationPreconditionRequiredException(),
            update,
        ),
    )

    private fun preferenceResponse(
        versioned: VersionedNotificationPreference,
    ): ResponseEntity<NotificationPreferenceView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(versioned.entityTag)
        .body(versioned.preference)
}
