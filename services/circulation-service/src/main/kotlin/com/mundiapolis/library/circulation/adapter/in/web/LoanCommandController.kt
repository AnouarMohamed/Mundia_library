package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.CommandExecution
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.LoanCommandResult
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanUseCase
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
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
@RequestMapping("/api/v1/circulation/loans")
class LoanCommandController(
    private val requestLoanUseCase: RequestLoanUseCase,
    private val approveLoanUseCase: ApproveLoanUseCase,
    private val renewLoanUseCase: RenewLoanUseCase,
    private val returnLoanUseCase: ReturnLoanUseCase,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @PostMapping
    @PreAuthorize(
        "hasAnyAuthority(" +
            "'SCOPE_circulation.loan.request'," +
            "'SCOPE_circulation.loan.request.on-behalf')",
    )
    fun request(
        authentication: JwtAuthenticationToken,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
        @RequestBody request: RequestLoanRequest,
    ): ResponseEntity<LoanCommandResponse> {
        val execution = requestLoanUseCase.request(
            RequestLoanCommand(
                memberId = MemberId(request.memberId),
                editionId = EditionId(request.editionId),
                idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                principal = principalResolver.forRequest(authentication),
            ),
        )

        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/circulation/loans/${execution.result.loanId.value}"))
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())
    }

    @PostMapping("/{loanId}/approve")
    @PreAuthorize("hasAuthority('SCOPE_circulation.loan.approve')")
    fun approve(
        authentication: JwtAuthenticationToken,
        @PathVariable loanId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
    ): ResponseEntity<LoanCommandResponse> {
        val execution = approveLoanUseCase.approve(
            ApproveLoanCommand(
                loanId = LoanId(loanId),
                idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                principal = principalResolver.forAdministrativeCommand(authentication),
            ),
        )
        return ok(execution)
    }

    @PostMapping("/{loanId}/return")
    @PreAuthorize("hasAuthority('SCOPE_circulation.loan.return')")
    fun returnLoan(
        authentication: JwtAuthenticationToken,
        @PathVariable loanId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
    ): ResponseEntity<LoanCommandResponse> {
        val execution = returnLoanUseCase.returnLoan(
            ReturnLoanCommand(
                loanId = LoanId(loanId),
                idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                principal = principalResolver.forAdministrativeCommand(authentication),
            ),
        )
        return ok(execution)
    }

    @PostMapping("/{loanId}/renew")
    @PreAuthorize(
        "hasAnyAuthority(" +
            "'SCOPE_circulation.loan.renew'," +
            "'SCOPE_circulation.loan.renew.on-behalf')",
    )
    fun renew(
        authentication: JwtAuthenticationToken,
        @PathVariable loanId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) rawIdempotencyKey: String,
    ): ResponseEntity<LoanCommandResponse> {
        val execution = renewLoanUseCase.renew(
            RenewLoanCommand(
                loanId = LoanId(loanId),
                idempotencyKey = IdempotencyKey.parse(rawIdempotencyKey),
                principal = principalResolver.forRenewal(authentication),
            ),
        )
        return ok(execution)
    }

    private fun ok(execution: CommandExecution): ResponseEntity<LoanCommandResponse> =
        ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, execution.replayed.toString())
            .body(execution.toResponse())

    private fun CommandExecution.toResponse(): LoanCommandResponse =
        LoanCommandResponse.from(result)

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}

data class RequestLoanRequest(
    val memberId: UUID,
    val editionId: UUID,
)

data class LoanCommandResponse(
    val loanId: UUID,
    val memberId: UUID,
    val editionId: UUID,
    val copyId: UUID?,
    val status: LoanStatus,
    val requestedAt: Instant,
    val checkedOutAt: Instant?,
    val dueAt: Instant?,
    val returnedAt: Instant?,
    val renewalCount: Int,
    val version: Long,
) {
    companion object {
        fun from(result: LoanCommandResult): LoanCommandResponse = LoanCommandResponse(
            loanId = result.loanId.value,
            memberId = result.memberId.value,
            editionId = result.editionId.value,
            copyId = result.copyId?.value,
            status = result.status,
            requestedAt = result.requestedAt,
            checkedOutAt = result.checkedOutAt,
            dueAt = result.dueAt,
            returnedAt = result.returnedAt,
            renewalCount = result.renewalCount,
            version = result.version,
        )
    }
}
