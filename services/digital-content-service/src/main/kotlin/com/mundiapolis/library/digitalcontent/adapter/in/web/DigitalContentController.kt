package com.mundiapolis.library.digitalcontent.adapter.`in`.web

import com.mundiapolis.library.digitalcontent.dto.EditionDownloadAvailability
import com.mundiapolis.library.digitalcontent.service.DigitalContentService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.TimeUnit

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
}
