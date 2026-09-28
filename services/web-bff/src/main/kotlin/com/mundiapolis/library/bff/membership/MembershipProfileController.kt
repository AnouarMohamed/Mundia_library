package com.mundiapolis.library.bff.membership

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/membership")
class MembershipProfileController(
    private val membershipProfile: MembershipProfileUseCase,
) {
    @GetMapping("/profile")
    fun profile(
        authentication: OAuth2AuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MemberProfileView> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(membershipProfile.profile(authentication, request, response))
}
