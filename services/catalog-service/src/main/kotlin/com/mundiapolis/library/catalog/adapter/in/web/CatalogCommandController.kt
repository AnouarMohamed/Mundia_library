package com.mundiapolis.library.catalog.adapter.`in`.web

import com.mundiapolis.library.catalog.dto.CatalogAuthorInput
import com.mundiapolis.library.catalog.dto.CatalogCommandExecution
import com.mundiapolis.library.catalog.dto.CreateEditionCommand
import com.mundiapolis.library.catalog.dto.CreateWorkCommand
import com.mundiapolis.library.catalog.service.CatalogCommandService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/catalog")
class CatalogCommandController(
    private val commandService: CatalogCommandService,
    private val principalResolver: CatalogCommandPrincipalResolver,
) {
    @PostMapping("/works")
    @PreAuthorize("hasAuthority('SCOPE_catalog.manage')")
    fun createWork(
        authentication: JwtAuthenticationToken,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: CreateWorkRequest,
    ): ResponseEntity<CatalogCommandResponse> {
        val execution = commandService.createWork(
            CreateWorkCommand(
                workId = request.workId,
                title = request.title,
                summary = request.summary,
                description = request.description,
                genre = request.genre,
                authors = request.authors.map { author ->
                    CatalogAuthorInput(author.contributorId, author.name, author.bio)
                },
                reason = request.reason,
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        )
        return created(execution, "/api/v1/catalog/works/${execution.result.aggregateId}")
    }

    @PostMapping("/works/{workId}/editions")
    @PreAuthorize("hasAuthority('SCOPE_catalog.manage')")
    fun createEdition(
        authentication: JwtAuthenticationToken,
        @PathVariable workId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: CreateEditionRequest,
    ): ResponseEntity<CatalogCommandResponse> {
        val execution = commandService.createEdition(
            CreateEditionCommand(
                editionId = request.editionId,
                workId = workId,
                title = request.title,
                isbn = request.isbn,
                publisher = request.publisher,
                publicationYear = request.publicationYear,
                language = request.language,
                pageCount = request.pageCount,
                coverUrl = request.coverUrl,
                coverColor = request.coverColor,
                videoUrl = request.videoUrl,
                active = request.isActive,
                reason = request.reason,
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        )
        return created(execution, "/api/v1/catalog/editions/${execution.result.aggregateId}")
    }

    private fun created(
        execution: CatalogCommandExecution,
        location: String,
    ): ResponseEntity<CatalogCommandResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .location(URI.create(location))
        .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
        .body(CatalogCommandResponse.from(execution))

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}

data class CreateWorkRequest(
    val workId: UUID,
    val title: String,
    val summary: String,
    val description: String,
    val genre: String,
    val authors: List<CreateAuthorRequest>,
    val reason: String,
)

data class CreateAuthorRequest(
    val contributorId: UUID,
    val name: String,
    val bio: String?,
)

data class CreateEditionRequest(
    val editionId: UUID,
    val title: String,
    val isbn: String,
    val publisher: String,
    val publicationYear: Int,
    val language: String,
    val pageCount: Int,
    val coverUrl: String?,
    val coverColor: String?,
    val videoUrl: String?,
    val isActive: Boolean = true,
    val reason: String,
)

data class CatalogCommandResponse(
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val occurredAt: Instant,
) {
    companion object {
        fun from(execution: CatalogCommandExecution): CatalogCommandResponse =
            CatalogCommandResponse(
                aggregateType = execution.result.aggregateType,
                aggregateId = execution.result.aggregateId,
                aggregateVersion = execution.result.aggregateVersion,
                occurredAt = execution.result.occurredAt,
            )
    }
}
