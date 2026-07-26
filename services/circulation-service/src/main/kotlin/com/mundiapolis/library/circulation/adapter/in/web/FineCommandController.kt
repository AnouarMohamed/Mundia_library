package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.FineCommandExecution
import com.mundiapolis.library.circulation.application.model.FineCommandResult
import com.mundiapolis.library.circulation.application.model.FineNarrative
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.PaymentReference
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentCommand
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentUseCase
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryType
import com.mundiapolis.library.circulation.domain.model.FineStatus
import com.mundiapolis.library.circulation.domain.model.LoanId
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
@RequestMapping("/api/v1/circulation/fines")
class FineCommandController(
    private val assessFineUseCase: AssessFineUseCase,
    private val recordFinePaymentUseCase: RecordFinePaymentUseCase,
    private val adjustFineUseCase: AdjustFineUseCase,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_circulation.fine.assess')")
    fun assess(
        authentication: JwtAuthenticationToken,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: AssessFineRequest,
    ): ResponseEntity<FineCommandResponse> {
        val execution = assessFineUseCase.assess(
            AssessFineCommand(
                loanId = LoanId(request.loanId),
                amountMinor = request.amountMinor,
                reason = FineNarrative.parse(request.reason),
                idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                principal = principalResolver.forAdministrativeCommand(authentication),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/circulation/fines/${execution.result.fineId.value}"))
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())
    }

    @PostMapping("/{fineId}/payments")
    @PreAuthorize("hasAuthority('SCOPE_circulation.fine.payment.record')")
    fun recordPayment(
        authentication: JwtAuthenticationToken,
        @PathVariable fineId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: RecordFinePaymentRequest,
    ): ResponseEntity<FineCommandResponse> {
        val execution = recordFinePaymentUseCase.recordPayment(
            RecordFinePaymentCommand(
                fineId = FineId(fineId),
                amountMinor = request.amountMinor,
                externalReference = PaymentReference.parse(request.externalReference),
                idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                principal = principalResolver.forAdministrativeCommand(authentication),
            ),
        )
        return ok(execution)
    }

    @PostMapping("/{fineId}/adjustments")
    @PreAuthorize("hasAuthority('SCOPE_circulation.fine.adjust')")
    fun adjust(
        authentication: JwtAuthenticationToken,
        @PathVariable fineId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: AdjustFineRequest,
    ): ResponseEntity<FineCommandResponse> {
        val execution = adjustFineUseCase.adjust(
            AdjustFineCommand(
                fineId = FineId(fineId),
                deltaMinor = request.deltaMinor,
                reason = FineNarrative.parse(request.reason),
                idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                principal = principalResolver.forAdministrativeCommand(authentication),
            ),
        )
        return ok(execution)
    }

    private fun ok(execution: FineCommandExecution): ResponseEntity<FineCommandResponse> =
        ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())

    private fun FineCommandExecution.toResponse(): FineCommandResponse =
        FineCommandResponse.from(result)

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}

data class AssessFineRequest(
    val loanId: UUID,
    val amountMinor: Long,
    val reason: String,
)

data class RecordFinePaymentRequest(
    val amountMinor: Long,
    val externalReference: String,
)

data class AdjustFineRequest(
    val deltaMinor: Long,
    val reason: String,
)

data class FineCommandResponse(
    val fineId: UUID,
    val loanId: UUID,
    val memberId: UUID,
    val currency: String,
    val balanceMinor: Long,
    val status: FineStatus,
    val version: Long,
    val ledgerEntryId: UUID,
    val ledgerEntryType: FineLedgerEntryType,
    val ledgerDeltaMinor: Long,
    val occurredAt: Instant,
) {
    companion object {
        fun from(result: FineCommandResult): FineCommandResponse = FineCommandResponse(
            fineId = result.fineId.value,
            loanId = result.loanId.value,
            memberId = result.memberId.value,
            currency = result.currency,
            balanceMinor = result.balanceMinor,
            status = result.status,
            version = result.version,
            ledgerEntryId = result.ledgerEntryId.value,
            ledgerEntryType = result.ledgerEntryType,
            ledgerDeltaMinor = result.ledgerDeltaMinor,
            occurredAt = result.occurredAt,
        )
    }
}
