package com.mundiapolis.library.catalog.adapter.`in`.web

import com.mundiapolis.library.catalog.dto.CatalogAuthorInput
import com.mundiapolis.library.catalog.dto.CatalogCommandExecution
import com.mundiapolis.library.catalog.dto.CreateEditionCommand
import com.mundiapolis.library.catalog.dto.CreateReviewCommand
import com.mundiapolis.library.catalog.dto.CreateWorkCommand
import com.mundiapolis.library.catalog.dto.DeleteReviewCommand
import com.mundiapolis.library.catalog.dto.InvalidCatalogCommandException
import com.mundiapolis.library.catalog.dto.SetEditionActiveCommand
import com.mundiapolis.library.catalog.dto.UpdateEditionCommand
import com.mundiapolis.library.catalog.dto.UpdateReviewCommand
import com.mundiapolis.library.catalog.dto.UpdateWorkCommand
import com.mundiapolis.library.catalog.service.CatalogCommandService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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

    @PutMapping("/works/{workId}")
    @PreAuthorize("hasAuthority('SCOPE_catalog.manage')")
    fun updateWork(
        authentication: JwtAuthenticationToken,
        @PathVariable workId: UUID,
        @RequestHeader(IF_MATCH_HEADER) ifMatch: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: UpdateWorkRequest,
    ): ResponseEntity<CatalogCommandResponse> = ok(
        commandService.updateWork(
            UpdateWorkCommand(
                workId = workId,
                expectedVersion = parseIfMatch(ifMatch),
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
        ),
    )

    @PutMapping("/editions/{editionId}")
    @PreAuthorize("hasAuthority('SCOPE_catalog.manage')")
    fun updateEdition(
        authentication: JwtAuthenticationToken,
        @PathVariable editionId: UUID,
        @RequestHeader(IF_MATCH_HEADER) ifMatch: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: UpdateEditionRequest,
    ): ResponseEntity<CatalogCommandResponse> = ok(
        commandService.updateEdition(
            UpdateEditionCommand(
                editionId = editionId,
                expectedVersion = parseIfMatch(ifMatch),
                title = request.title,
                isbn = request.isbn,
                publisher = request.publisher,
                publicationYear = request.publicationYear,
                language = request.language,
                pageCount = request.pageCount,
                coverUrl = request.coverUrl,
                coverColor = request.coverColor,
                videoUrl = request.videoUrl,
                reason = request.reason,
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        ),
    )

    @PostMapping("/editions/{editionId}/activation")
    @PreAuthorize("hasAuthority('SCOPE_catalog.manage')")
    fun setEditionActive(
        authentication: JwtAuthenticationToken,
        @PathVariable editionId: UUID,
        @RequestHeader(IF_MATCH_HEADER) ifMatch: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: SetEditionActiveRequest,
    ): ResponseEntity<CatalogCommandResponse> = ok(
        commandService.setEditionActive(
            SetEditionActiveCommand(
                editionId = editionId,
                expectedVersion = parseIfMatch(ifMatch),
                active = request.isActive,
                reason = request.reason,
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        ),
    )

    @PostMapping("/works/{workId}/reviews")
    @PreAuthorize("hasAuthority('SCOPE_catalog.review.write')")
    fun createReview(
        authentication: JwtAuthenticationToken,
        @PathVariable workId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: CreateReviewRequest,
    ): ResponseEntity<CatalogCommandResponse> {
        val execution = commandService.createReview(
            CreateReviewCommand(
                workId = workId,
                memberId = principalResolver.membershipId(authentication),
                rating = request.rating,
                content = request.content,
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        )
        return created(execution, "/api/v1/catalog/reviews/${execution.result.aggregateId}")
    }

    @PutMapping("/reviews/{reviewId}")
    @PreAuthorize("hasAuthority('SCOPE_catalog.review.write')")
    fun updateReview(
        authentication: JwtAuthenticationToken,
        @PathVariable reviewId: UUID,
        @RequestHeader(IF_MATCH_HEADER) ifMatch: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: UpdateReviewRequest,
    ): ResponseEntity<CatalogCommandResponse> = ok(
        commandService.updateReview(
            UpdateReviewCommand(
                reviewId = reviewId,
                memberId = principalResolver.membershipId(authentication),
                expectedVersion = parseIfMatch(ifMatch),
                rating = request.rating,
                content = request.content,
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        ),
    )

    @DeleteMapping("/reviews/{reviewId}")
    @PreAuthorize("hasAuthority('SCOPE_catalog.review.write')")
    fun deleteReview(
        authentication: JwtAuthenticationToken,
        @PathVariable reviewId: UUID,
        @RequestHeader(IF_MATCH_HEADER) ifMatch: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
    ): ResponseEntity<CatalogCommandResponse> = ok(
        commandService.deleteReview(
            DeleteReviewCommand(
                reviewId = reviewId,
                memberId = principalResolver.membershipId(authentication),
                expectedVersion = parseIfMatch(ifMatch),
                idempotencyKey = idempotencyKey,
                ownerFingerprint = principalResolver.ownerFingerprint(authentication),
            ),
        ),
    )

    private fun created(
        execution: CatalogCommandExecution,
        location: String,
    ): ResponseEntity<CatalogCommandResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .location(URI.create(location))
        .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
        .eTag("\"${execution.result.aggregateVersion}\"")
        .body(CatalogCommandResponse.from(execution))

    private fun ok(execution: CatalogCommandExecution): ResponseEntity<CatalogCommandResponse> =
        ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .eTag("\"${execution.result.aggregateVersion}\"")
            .body(CatalogCommandResponse.from(execution))

    private fun parseIfMatch(value: String): Long {
        val match = VERSION_ETAG.matchEntire(value)
            ?: throw InvalidCatalogCommandException("If-Match must be one quoted aggregate version")
        return match.groupValues[1].toLongOrNull()
            ?: throw InvalidCatalogCommandException("If-Match aggregate version is too large")
    }

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val IF_MATCH_HEADER = "If-Match"
        val VERSION_ETAG = Regex("\"(0|[1-9][0-9]*)\"")
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

data class UpdateWorkRequest(
    val title: String,
    val summary: String,
    val description: String,
    val genre: String,
    val authors: List<CreateAuthorRequest>,
    val reason: String,
)

data class UpdateEditionRequest(
    val title: String,
    val isbn: String,
    val publisher: String,
    val publicationYear: Int,
    val language: String,
    val pageCount: Int,
    val coverUrl: String?,
    val coverColor: String?,
    val videoUrl: String?,
    val reason: String,
)

data class SetEditionActiveRequest(
    val isActive: Boolean,
    val reason: String,
)

data class CreateReviewRequest(
    val rating: Int,
    val content: String,
)

data class UpdateReviewRequest(
    val rating: Int,
    val content: String,
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
