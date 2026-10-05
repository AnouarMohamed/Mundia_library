package com.mundiapolis.library.digitalcontent.adapter.`in`.web

import com.mundiapolis.library.digitalcontent.dto.EditionDownloadAvailability
import com.mundiapolis.library.digitalcontent.dto.DownloadAuthorization
import com.mundiapolis.library.digitalcontent.dto.CreateIngestionRequest
import com.mundiapolis.library.digitalcontent.dto.IngestionUploadGrant
import com.mundiapolis.library.digitalcontent.dto.ExternalDownloadAuthorization
import com.mundiapolis.library.digitalcontent.dto.ExternalResourceAvailability
import com.mundiapolis.library.digitalcontent.dto.ExternalResourceRegistration
import com.mundiapolis.library.digitalcontent.dto.RegisterExternalResourceRequest
import com.mundiapolis.library.digitalcontent.service.DigitalContentService
import com.mundiapolis.library.digitalcontent.service.ExternalResourceService
import com.mundiapolis.library.digitalcontent.service.IngestionService
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
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
    private val ingestionService: IngestionService,
    private val externalResourceService: ExternalResourceService,
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

    @PutMapping("/ingestions/{ingestionId}")
    @PreAuthorize("hasAuthority('SCOPE_digital-content.ingestion.create')")
    fun createIngestion(
        authentication: JwtAuthenticationToken,
        @PathVariable ingestionId: UUID,
        @Valid @RequestBody request: CreateIngestionRequest,
    ): ResponseEntity<IngestionUploadGrant> {
        val grant = ingestionService.create(ingestionId, request, authentication.actorFingerprint())
        return ResponseEntity.status(if (grant.replayed) 200 else 201)
            .cacheControl(CacheControl.noStore())
            .body(grant)
    }

    @PutMapping("/external-resources/{resourceId}")
    @PreAuthorize("hasAuthority('SCOPE_digital-content.external-resource.manage')")
    fun registerExternalResource(
        authentication: JwtAuthenticationToken,
        @PathVariable resourceId: UUID,
        @Valid @RequestBody request: RegisterExternalResourceRequest,
    ): ResponseEntity<ExternalResourceRegistration> {
        val registration = externalResourceService.register(
            resourceId,
            request,
            authentication.actorFingerprint(),
        )
        return ResponseEntity.status(if (registration.replayed) 200 else 201)
            .cacheControl(CacheControl.noStore())
            .body(registration)
    }

    @GetMapping("/external-resources/{resourceId}/availability")
    @PreAuthorize("hasAuthority('SCOPE_digital-content.availability.read')")
    fun externalAvailability(
        @PathVariable resourceId: UUID,
    ): ResponseEntity<ExternalResourceAvailability> = ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate().mustRevalidate())
        .body(externalResourceService.availability(resourceId))

    @PostMapping("/external-resources/{resourceId}/authorizations")
    @PreAuthorize("hasAuthority('SCOPE_digital-content.download.authorize')")
    fun authorizeExternalResource(
        authentication: JwtAuthenticationToken,
        @PathVariable resourceId: UUID,
    ): ResponseEntity<ExternalDownloadAuthorization> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(externalResourceService.authorize(resourceId, authentication.actorFingerprint()))

    private fun JwtAuthenticationToken.actorFingerprint(): String {
        val issuer = token.issuer?.toString().orEmpty()
        val subject = token.subject.orEmpty()
        require(issuer.isNotBlank() && subject.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$issuer\u0000$subject".toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
