package com.mundiapolis.library.digitalcontent.adapter.`in`.web

import com.mundiapolis.library.digitalcontent.dto.EditionDownloadAvailability
import com.mundiapolis.library.digitalcontent.dto.DownloadAuthorization
import com.mundiapolis.library.digitalcontent.service.DigitalContentService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

@RestController
@RequestMapping("/api/v1/digital-content")
class DigitalContentController(
    private val service: DigitalContentService,
) {
    @GetMapping("/editions/{editionId}/availability")
    @PreAuthorize("hasAuthority('SCOPE_digital-content.availability.read')")
    fun availability(
        @PathVariable editionId: UUID,
    ): ResponseEntity<EditionDownloadAvailability> = ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate().mustRevalidate())
        .body(service.availability(editionId))

    @PostMapping("/assets/{assetId}/authorizations")
    @PreAuthorize("hasAuthority('SCOPE_digital-content.download.authorize')")
    fun authorize(
        authentication: JwtAuthenticationToken,
        @PathVariable assetId: UUID,
    ): ResponseEntity<DownloadAuthorization> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.authorize(assetId, authentication.actorFingerprint()))

    private fun JwtAuthenticationToken.actorFingerprint(): String {
        val issuer = token.issuer?.toString().orEmpty()
        val subject = token.subject.orEmpty()
        require(issuer.isNotBlank() && subject.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$issuer\u0000$subject".toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
