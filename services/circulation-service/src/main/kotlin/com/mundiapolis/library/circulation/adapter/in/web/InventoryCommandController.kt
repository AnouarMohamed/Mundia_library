package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.InvalidInventoryInputException
import com.mundiapolis.library.circulation.application.model.InventoryCommandExecution
import com.mundiapolis.library.circulation.application.model.InventoryCommandResult
import com.mundiapolis.library.circulation.application.port.inbound.ChangeCopyConditionCommand
import com.mundiapolis.library.circulation.application.port.inbound.ChangeCopyConditionUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RegisterCopyCommand
import com.mundiapolis.library.circulation.application.port.inbound.RegisterCopyUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RelocateCopyCommand
import com.mundiapolis.library.circulation.application.port.inbound.RelocateCopyUseCase
import com.mundiapolis.library.circulation.domain.model.BranchId
import com.mundiapolis.library.circulation.domain.model.CopyBarcode
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.CopyStatus
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.InventoryReason
import com.mundiapolis.library.circulation.domain.model.ShelfLocation
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
@RequestMapping("/api/v1/circulation/copies")
class InventoryCommandController(
    private val registerCopyUseCase: RegisterCopyUseCase,
    private val changeCopyConditionUseCase: ChangeCopyConditionUseCase,
    private val relocateCopyUseCase: RelocateCopyUseCase,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_circulation.inventory.register')")
    fun register(
        authentication: JwtAuthenticationToken,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: RegisterCopyRequest,
    ): ResponseEntity<InventoryCommandResponse> {
        val execution = parseInventoryInput {
            registerCopyUseCase.register(
                RegisterCopyCommand(
                    copyId = CopyId(request.copyId),
                    editionId = EditionId(request.editionId),
                    branchId = BranchId(request.branchId),
                    barcode = CopyBarcode.parse(request.barcode),
                    shelfLocation = request.shelfLocation?.let(ShelfLocation::parse),
                    reason = InventoryReason.parse(request.reason),
                    idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                    principal = principalResolver.forAdministrativeCommand(authentication),
                ),
            )
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/circulation/copies/${execution.result.copyId.value}"))
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())
    }

    @PostMapping("/{copyId}/condition")
    @PreAuthorize("hasAuthority('SCOPE_circulation.inventory.condition.update')")
    fun changeCondition(
        authentication: JwtAuthenticationToken,
        @PathVariable copyId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: ChangeCopyConditionRequest,
    ): ResponseEntity<InventoryCommandResponse> = ok(
        parseInventoryInput {
            changeCopyConditionUseCase.changeCondition(
                ChangeCopyConditionCommand(
                    copyId = CopyId(copyId),
                    target = request.status,
                    reason = InventoryReason.parse(request.reason),
                    idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                    principal = principalResolver.forAdministrativeCommand(authentication),
                ),
            )
        },
    )

    @PostMapping("/{copyId}/relocations")
    @PreAuthorize("hasAuthority('SCOPE_circulation.inventory.relocate')")
    fun relocate(
        authentication: JwtAuthenticationToken,
        @PathVariable copyId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: RelocateCopyRequest,
    ): ResponseEntity<InventoryCommandResponse> = ok(
        parseInventoryInput {
            relocateCopyUseCase.relocate(
                RelocateCopyCommand(
                    copyId = CopyId(copyId),
                    branchId = BranchId(request.branchId),
                    shelfLocation = ShelfLocation.parse(request.shelfLocation),
                    reason = InventoryReason.parse(request.reason),
                    idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                    principal = principalResolver.forAdministrativeCommand(authentication),
                ),
            )
        },
    )

    private fun ok(execution: InventoryCommandExecution): ResponseEntity<InventoryCommandResponse> =
        ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())

    private fun InventoryCommandExecution.toResponse(): InventoryCommandResponse =
        InventoryCommandResponse.from(result)

    private fun <T> parseInventoryInput(block: () -> T): T = try {
        block()
    } catch (exception: IllegalArgumentException) {
        throw InvalidInventoryInputException(
            exception.message ?: "Inventory command input is invalid",
        )
    }

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}

data class RegisterCopyRequest(
    val copyId: UUID,
    val editionId: UUID,
    val branchId: UUID,
    val barcode: String,
    val shelfLocation: String?,
    val reason: String,
)

data class ChangeCopyConditionRequest(
    val status: CopyStatus,
    val reason: String,
)

data class RelocateCopyRequest(
    val branchId: UUID,
    val shelfLocation: String,
    val reason: String,
)

data class InventoryCommandResponse(
    val copyId: UUID,
    val editionId: UUID,
    val branchId: UUID,
    val barcode: String,
    val status: CopyStatus,
    val shelfLocation: String?,
    val version: Long,
    val occurredAt: Instant,
) {
    companion object {
        fun from(result: InventoryCommandResult): InventoryCommandResponse =
            InventoryCommandResponse(
                copyId = result.copyId.value,
                editionId = result.editionId.value,
                branchId = result.branchId.value,
                barcode = result.barcode,
                status = result.status,
                shelfLocation = result.shelfLocation,
                version = result.version,
                occurredAt = result.occurredAt,
            )
    }
}
