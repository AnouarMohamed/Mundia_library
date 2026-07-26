package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.DuplicatePaymentReferenceException
import com.mundiapolis.library.circulation.application.model.FineBalanceConflictException
import com.mundiapolis.library.circulation.application.model.FineNotFoundException
import com.mundiapolis.library.circulation.application.model.FinePersistenceConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException
import com.mundiapolis.library.circulation.application.model.IncompleteIdempotencyRecordException
import com.mundiapolis.library.circulation.application.model.InvalidActorIdentityException
import com.mundiapolis.library.circulation.application.model.InvalidAuthenticationClaimException
import com.mundiapolis.library.circulation.application.model.InvalidFineAdjustmentException
import com.mundiapolis.library.circulation.application.model.InvalidFineAmountException
import com.mundiapolis.library.circulation.application.model.InvalidFineNarrativeException
import com.mundiapolis.library.circulation.application.model.InvalidIdempotencyKeyException
import com.mundiapolis.library.circulation.application.model.InvalidPaymentReferenceException
import com.mundiapolis.library.circulation.application.model.LoanNotEligibleForFineException
import com.mundiapolis.library.circulation.application.model.LoanNotFoundException
import com.mundiapolis.library.circulation.application.model.LoanOverdueException
import com.mundiapolis.library.circulation.application.model.LoanStateConflictException
import com.mundiapolis.library.circulation.application.model.MemberAccessDeniedException
import com.mundiapolis.library.circulation.application.model.MissingMembershipClaimException
import com.mundiapolis.library.circulation.application.model.NoAvailableCopyException
import com.mundiapolis.library.circulation.application.model.OpenLoanAlreadyExistsException
import com.mundiapolis.library.circulation.application.model.RenewalLimitReachedException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class CirculationExceptionHandler {
    @ExceptionHandler(InvalidIdempotencyKeyException::class)
    fun invalidIdempotencyKey(exception: InvalidIdempotencyKeyException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", exception.message)

    @ExceptionHandler(
        InvalidFineAmountException::class,
        InvalidFineAdjustmentException::class,
        InvalidFineNarrativeException::class,
        InvalidPaymentReferenceException::class,
    )
    fun invalidFineCommand(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_fine_command", exception.message)

    @ExceptionHandler(InvalidActorIdentityException::class)
    fun invalidActorIdentity(exception: InvalidActorIdentityException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "invalid_actor_identity", exception.message)

    @ExceptionHandler(InvalidAuthenticationClaimException::class)
    fun invalidAuthenticationClaim(exception: InvalidAuthenticationClaimException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "invalid_authentication_claim", exception.message)

    @ExceptionHandler(MissingMembershipClaimException::class)
    fun missingMembershipClaim(exception: MissingMembershipClaimException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "missing_membership_claim", exception.message)

    @ExceptionHandler(MemberAccessDeniedException::class)
    fun memberAccessDenied(exception: MemberAccessDeniedException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "member_access_denied", exception.message)

    @ExceptionHandler(LoanNotFoundException::class)
    fun loanNotFound(exception: LoanNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "loan_not_found", exception.message)

    @ExceptionHandler(FineNotFoundException::class)
    fun fineNotFound(exception: FineNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "fine_not_found", exception.message)

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    fun idempotencyConflict(exception: IdempotencyKeyConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "idempotency_key_conflict", exception.message)

    @ExceptionHandler(OpenLoanAlreadyExistsException::class)
    fun openLoanConflict(exception: OpenLoanAlreadyExistsException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "open_loan_already_exists", exception.message)

    @ExceptionHandler(LoanStateConflictException::class)
    fun loanStateConflict(exception: LoanStateConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "loan_state_conflict", exception.message)

    @ExceptionHandler(LoanOverdueException::class)
    fun loanOverdue(exception: LoanOverdueException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "loan_overdue", exception.message)

    @ExceptionHandler(RenewalLimitReachedException::class)
    fun renewalLimit(exception: RenewalLimitReachedException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "renewal_limit_reached", exception.message)

    @ExceptionHandler(LoanNotEligibleForFineException::class)
    fun loanNotEligibleForFine(exception: LoanNotEligibleForFineException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "loan_not_eligible_for_fine", exception.message)

    @ExceptionHandler(FineBalanceConflictException::class)
    fun fineBalanceConflict(exception: FineBalanceConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "fine_balance_conflict", exception.message)

    @ExceptionHandler(DuplicatePaymentReferenceException::class)
    fun duplicatePaymentReference(exception: DuplicatePaymentReferenceException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "duplicate_payment_reference", exception.message)

    @ExceptionHandler(FinePersistenceConflictException::class)
    fun finePersistenceConflict(exception: FinePersistenceConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "concurrent_fine_update", exception.message)

    @ExceptionHandler(NoAvailableCopyException::class)
    fun noAvailableCopy(exception: NoAvailableCopyException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "no_available_copy", exception.message)

    @ExceptionHandler(ConcurrentCirculationUpdateException::class)
    fun concurrentUpdate(exception: ConcurrentCirculationUpdateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "concurrent_circulation_update", exception.message)

    @ExceptionHandler(IncompleteIdempotencyRecordException::class)
    fun incompleteIdempotency(exception: IncompleteIdempotencyRecordException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "idempotency_result_unavailable", exception.message)

    private fun problem(
        status: HttpStatus,
        code: String,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, requireNotNull(detail)).apply {
        title = status.reasonPhrase
        type = URI.create("urn:mundia:error:$code")
        setProperty("code", code)
    }
}
