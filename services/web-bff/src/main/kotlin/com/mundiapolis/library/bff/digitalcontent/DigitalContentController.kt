package com.mundiapolis.library.bff.digitalcontent

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/digital-content")
class DigitalContentController(
    private val digitalContent: DigitalContentSelfServiceUseCase,
) {
    @GetMapping("/editions/{editionId}/availability")
    fun availability(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable editionId: UUID,
    ): ResponseEntity<EditionDownloadAvailabilityView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(digitalContent.availability(authentication, request, response, editionId))

    @PostMapping("/assets/{assetId}/authorizations")
    fun authorize(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable assetId: UUID,
    ): ResponseEntity<DownloadAuthorizationView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(digitalContent.authorize(authentication, request, response, assetId))
}
