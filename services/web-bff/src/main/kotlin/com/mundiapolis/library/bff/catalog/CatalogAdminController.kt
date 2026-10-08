package com.mundiapolis.library.bff.catalog

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/admin/catalog")
class CatalogAdminController(private val catalog: CatalogAdminUseCase) {
    @GetMapping("/editions")
    fun editions(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse,
        @RequestParam(required = false) @Size(max = 200) query: String?,
        @RequestParam(required = false) @Min(0) @Max(10_000) page: Int?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ) = noStore(catalog.editions(auth, request, response, query, page, limit))

    @GetMapping("/works/{workId}")
    fun work(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse, @PathVariable workId: UUID) =
        noStore(catalog.work(auth, request, response, workId))

    @PostMapping("/works")
    fun createWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse,
        @RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody command: CreateCatalogWorkView,
    ) = created(catalog.createWork(auth, request, response, key, command))

    @PutMapping("/works/{workId}")
    fun updateWork(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse,
        @PathVariable workId: UUID, @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody command: UpdateCatalogWorkView,
    ) = ok(catalog.updateWork(auth, request, response, workId, version(ifMatch), key, command))

    @PostMapping("/works/{workId}/editions")
    fun createEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse,
        @PathVariable workId: UUID, @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody command: CreateCatalogEditionView,
    ) = created(catalog.createEdition(auth, request, response, workId, key, command))

    @PutMapping("/editions/{editionId}")
    fun updateEdition(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse,
        @PathVariable editionId: UUID, @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody command: UpdateCatalogEditionView,
    ) = ok(catalog.updateEdition(auth, request, response, editionId, version(ifMatch), key, command))

    @PostMapping("/editions/{editionId}/activation")
    fun setActive(auth: OAuth2AuthenticationToken, request: HttpServletRequest, response: HttpServletResponse,
        @PathVariable editionId: UUID, @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody command: SetCatalogEditionActiveView,
    ) = ok(catalog.setActive(auth, request, response, editionId, version(ifMatch), key, command))

    private fun version(value: String): Long {
        val match = ETAG.matchEntire(value) ?: throw CatalogInvalidRequestException()
        return match.groupValues[1].toLongOrNull() ?: throw CatalogInvalidRequestException()
    }

    private fun created(result: CatalogMutationResult) = response(result, 201)
    private fun ok(result: CatalogMutationResult) = response(result, 200)
    private fun response(result: CatalogMutationResult, status: Int): ResponseEntity<CatalogCommandView> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .eTag("\"${result.command.aggregateVersion}\"")
            .header("Idempotency-Replayed", result.replayed.toString()).body(result.command)
    private fun <T : Any> noStore(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private companion object { val ETAG = Regex("^\\\"(0|[1-9][0-9]*)\\\"$") }
}
