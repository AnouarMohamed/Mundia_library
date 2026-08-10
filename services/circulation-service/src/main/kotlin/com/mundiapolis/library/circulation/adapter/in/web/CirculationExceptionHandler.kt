package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.ConcurrentInventoryUpdateException
import com.mundiapolis.library.circulation.application.model.CopyAlreadyExistsException
import com.mundiapolis.library.circulation.application.model.CopyNotFoundException
import com.mundiapolis.library.circulation.application.model.CopyStateConflictException
import com.mundiapolis.library.circulation.application.model.DuplicatePaymentReferenceException
import com.mundiapolis.library.circulation.application.model.FineBalanceConflictException
import com.mundiapolis.library.circulation.application.model.FineCurrencyMismatchException
import com.mundiapolis.library.circulation.application.model.FineNotFoundException
import com.mundiapolis.library.circulation.application.model.FinePersistenceConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException
import com.mundiapolis.library.circulation.application.model.IncompleteIdempotencyRecordException
import com.mundiapolis.library.circulation.application.model.InvalidActorIdentityException
import com.mundiapolis.library.circulation.application.model.InvalidAuthenticationClaimException
import com.mundiapolis.library.circulation.application.model.InvalidFineAdjustmentException
import com.mundiapolis.library.circulation.application.model.InvalidFineAmountException
import com.mundiapolis.library.circulation.application.model.InvalidFineCurrencyException
import com.mundiapolis.library.circulation.application.model.InvalidFineNarrativeException
import com.mundiapolis.library.circulation.application.model.InvalidIdempotencyKeyException
import com.mundiapolis.library.circulation.application.model.InvalidInventoryInputException
import com.mundiapolis.library.circulation.application.model.InvalidPaymentReferenceException
import com.mundiapolis.library.circulation.application.model.LoanNotEligibleForFineException
import com.mundiapolis.library.circulation.application.model.LoanNotFoundException
import com.mundiapolis.library.circulation.application.model.LoanOverdueException
import com.mundiapolis.library.circulation.application.model.LoanStateConflictException
import com.mundiapolis.library.circulation.application.model.MemberAccessDeniedException
import com.mundiapolis.library.circulation.application.model.MemberEligibilityNotFoundException
import com.mundiapolis.library.circulation.application.model.MemberEligibilityUnavailableException
import com.mundiapolis.library.circulation.application.model.MemberNotEligibleException
import com.mundiapolis.library.circulation.application.model.MissingMembershipClaimException
import com.mundiapolis.library.circulation.application.model.NoAvailableCopyException
import com.mundiapolis.library.circulation.application.model.OpenLoanAlreadyExistsException
import com.mundiapolis.library.circulation.application.model.RenewalLimitReachedException
import com.mundiapolis.library.circulation.application.model.InvalidCirculationPolicyException
import com.mundiapolis.library.circulation.application.model.OpenLoanBlocksReservationException
import com.mundiapolis.library.circulation.application.model.OpenReservationAlreadyExistsException
import com.mundiapolis.library.circulation.application.model.OpenReservationBlocksLoanException
import com.mundiapolis.library.circulation.application.model.PendingReservationBlocksRenewalException
import com.mundiapolis.library.circulation.application.model.PolicyRevisionConflictException
import com.mundiapolis.library.circulation.application.model.PolicyRevisionNotFoundException
import com.mundiapolis.library.circulation.application.model.ReservationHoldExpiredException
import com.mundiapolis.library.circulation.application.model.ReservationHoldNotExpiredException
import com.mundiapolis.library.circulation.application.model.ReservationLimitReachedException
import com.mundiapolis.library.circulation.application.model.ReservationNotFoundException
import com.mundiapolis.library.circulation.application.model.ReservationStateConflictException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class CirculationExceptionHandler {
    @ExceptionHandler(InvalidCirculationPolicyException::class)
    fun invalidPolicy(exception: InvalidCirculationPolicyException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_circulation_policy", exception.message)
    @ExceptionHandler(InvalidIdempotencyKeyException::class)
    fun invalidIdempotencyKey(exception: InvalidIdempotencyKeyException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", exception.message)

    @ExceptionHandler(InvalidInventoryInputException::class)
    fun invalidInventoryInput(exception: InvalidInventoryInputException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_inventory_input", exception.message)

    @ExceptionHandler(
        InvalidFineAmountException::class,
        InvalidFineAdjustmentException::class,
        InvalidFineCurrencyException::class,
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

    @ExceptionHandler(MemberEligibilityNotFoundException::class)
    fun memberEligibilityNotFound(exception: MemberEligibilityNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "member_eligibility_not_found", exception.message)

    @ExceptionHandler(MemberEligibilityUnavailableException::class)
    fun memberEligibilityUnavailable(
        exception: MemberEligibilityUnavailableException,
    ): ProblemDetail =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "member_eligibility_unavailable", exception.message)

    @ExceptionHandler(MemberNotEligibleException::class)
    fun memberNotEligible(exception: MemberNotEligibleException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "member_not_eligible", exception.message)

    @ExceptionHandler(LoanNotFoundException::class)
    fun loanNotFound(exception: LoanNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "loan_not_found", exception.message)

    @ExceptionHandler(ReservationNotFoundException::class)
    fun reservationNotFound(exception: ReservationNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "reservation_not_found", exception.message)

    @ExceptionHandler(FineNotFoundException::class)
    fun fineNotFound(exception: FineNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "fine_not_found", exception.message)

    @ExceptionHandler(CopyNotFoundException::class)
    fun copyNotFound(exception: CopyNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "copy_not_found", exception.message)

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    fun idempotencyConflict(exception: IdempotencyKeyConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "idempotency_key_conflict", exception.message)

    @ExceptionHandler(OpenLoanAlreadyExistsException::class)
    fun openLoanConflict(exception: OpenLoanAlreadyExistsException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "open_loan_already_exists", exception.message)

    @ExceptionHandler(
        OpenReservationAlreadyExistsException::class,
        OpenLoanBlocksReservationException::class,
        OpenReservationBlocksLoanException::class,
        PendingReservationBlocksRenewalException::class,
        ReservationStateConflictException::class,
        ReservationHoldNotExpiredException::class,
        ReservationHoldExpiredException::class,
        PolicyRevisionConflictException::class,
    )
    fun reservationOrPolicyConflict(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "circulation_state_conflict", exception.message)

    @ExceptionHandler(ReservationLimitReachedException::class)
    fun reservationLimit(exception: ReservationLimitReachedException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "reservation_limit_reached", exception.message)

    @ExceptionHandler(PolicyRevisionNotFoundException::class)
    fun policyRevisionUnavailable(exception: PolicyRevisionNotFoundException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "policy_revision_unavailable", exception.message)

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

    @ExceptionHandler(FineCurrencyMismatchException::class)
    fun fineCurrencyMismatch(exception: FineCurrencyMismatchException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "fine_currency_mismatch", exception.message)

    @ExceptionHandler(DuplicatePaymentReferenceException::class)
    fun duplicatePaymentReference(exception: DuplicatePaymentReferenceException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "duplicate_payment_reference", exception.message)

    @ExceptionHandler(CopyAlreadyExistsException::class)
    fun copyAlreadyExists(exception: CopyAlreadyExistsException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "copy_already_exists", exception.message)

    @ExceptionHandler(CopyStateConflictException::class)
    fun copyStateConflict(exception: CopyStateConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "copy_state_conflict", exception.message)

    @ExceptionHandler(ConcurrentInventoryUpdateException::class)
    fun concurrentInventoryUpdate(exception: ConcurrentInventoryUpdateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "concurrent_inventory_update", exception.message)

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
