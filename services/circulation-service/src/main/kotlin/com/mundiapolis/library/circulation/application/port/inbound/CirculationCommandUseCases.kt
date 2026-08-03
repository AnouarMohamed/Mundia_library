package com.mundiapolis.library.circulation.application.port.inbound

import com.mundiapolis.library.circulation.application.model.CommandExecution
import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.FineCommandExecution
import com.mundiapolis.library.circulation.application.model.FineNarrative
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.PaymentReference
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.MemberId

data class RequestLoanCommand(
    val memberId: MemberId,
    val editionId: EditionId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class ApproveLoanCommand(
    val loanId: LoanId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class RejectLoanCommand(
    val loanId: LoanId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class CancelLoanCommand(
    val loanId: LoanId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class ReturnLoanCommand(
    val loanId: LoanId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class RenewLoanCommand(
    val loanId: LoanId,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class AssessFineCommand(
    val loanId: LoanId,
    val amountMinor: Long,
    val reason: FineNarrative,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class RecordFinePaymentCommand(
    val fineId: FineId,
    val amountMinor: Long,
    val externalReference: PaymentReference,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class AdjustFineCommand(
    val fineId: FineId,
    val deltaMinor: Long,
    val reason: FineNarrative,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

fun interface RequestLoanUseCase {
    fun request(command: RequestLoanCommand): CommandExecution
}

fun interface ApproveLoanUseCase {
    fun approve(command: ApproveLoanCommand): CommandExecution
}

fun interface RejectLoanUseCase {
    fun reject(command: RejectLoanCommand): CommandExecution
}

fun interface CancelLoanUseCase {
    fun cancel(command: CancelLoanCommand): CommandExecution
}

fun interface ReturnLoanUseCase {
    fun returnLoan(command: ReturnLoanCommand): CommandExecution
}

fun interface RenewLoanUseCase {
    fun renew(command: RenewLoanCommand): CommandExecution
}

fun interface AssessFineUseCase {
    fun assess(command: AssessFineCommand): FineCommandExecution
}

fun interface RecordFinePaymentUseCase {
    fun recordPayment(command: RecordFinePaymentCommand): FineCommandExecution
}

fun interface AdjustFineUseCase {
    fun adjust(command: AdjustFineCommand): FineCommandExecution
}
