package com.mundiapolis.library.bff.web

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class BffSessionController {
    @GetMapping("/session")
    fun session(authentication: Authentication?): ResponseEntity<SessionView> {
        val principal = authentication
            ?.takeIf(Authentication::isAuthenticated)
            ?.principal as? OidcUser
        val response = if (principal == null) {
            SessionView(authenticated = false, displayName = null)
        } else {
            SessionView(authenticated = true, displayName = principal.safeDisplayName())
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(response)
    }

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): ResponseEntity<CsrfView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(CsrfView(token.headerName, token.parameterName, token.token))

    private fun OidcUser.safeDisplayName(): String = sequenceOf(
        fullName,
        preferredUsername,
    ).filterNotNull()
        .map(String::trim)
        .firstOrNull { value ->
            value.isNotEmpty() && value.length <= MAXIMUM_DISPLAY_NAME_LENGTH &&
                value.none(Char::isISOControl)
        }
        ?: "Member"

    data class SessionView(
        val authenticated: Boolean,
        val displayName: String?,
    )

    data class CsrfView(
        val headerName: String,
        val parameterName: String,
        val token: String,
    )

    private companion object {
        const val MAXIMUM_DISPLAY_NAME_LENGTH = 120
    }
}
