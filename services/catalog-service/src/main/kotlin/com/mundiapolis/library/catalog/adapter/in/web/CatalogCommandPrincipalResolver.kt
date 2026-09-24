package com.mundiapolis.library.catalog.adapter.`in`.web

import com.mundiapolis.library.catalog.dto.InvalidCatalogActorException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

@Component
class CatalogCommandPrincipalResolver {
    fun ownerFingerprint(authentication: JwtAuthenticationToken): String {
        val jwt = authentication.token
        val issuer = jwt.issuer?.toString()
            ?: throw InvalidCatalogActorException("JWT issuer claim is required")
        val subject = optionalClaim(jwt, "sub")
        val authorizedParty = optionalClaim(jwt, "azp")
        val clientId = optionalClaim(jwt, "client_id")
        if (subject == null && authorizedParty == null && clientId == null) {
            throw InvalidCatalogActorException("JWT must identify a subject or client")
        }
        val canonical = listOf(
            "catalog-command-owner-v1",
            issuer,
            subject ?: NULL_MARKER,
            authorizedParty ?: NULL_MARKER,
            clientId ?: NULL_MARKER,
        ).joinToString("\u001f")
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun optionalClaim(jwt: Jwt, name: String): String? {
        val value = jwt.claims[name] ?: return null
        if (value !is String || value.isBlank() || value.any(Char::isISOControl)) {
            throw InvalidCatalogActorException("JWT $name claim is invalid")
        }
        return value
    }

    private companion object {
        const val NULL_MARKER = "<null>"
    }
}
