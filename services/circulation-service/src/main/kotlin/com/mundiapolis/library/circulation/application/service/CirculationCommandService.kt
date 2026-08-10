package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.CirculationOutboxEvent
import com.mundiapolis.library.circulation.application.model.CommandExecution
import com.mundiapolis.library.circulation.application.model.CommandOperation
import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.IncompleteIdempotencyRecordException
import com.mundiapolis.library.circulation.application.model.LoanCommandResult
import com.mundiapolis.library.circulation.application.model.LoanNotFoundException
import com.mundiapolis.library.circulation.application.model.LoanOverdueException
import com.mundiapolis.library.circulation.application.model.LoanStateConflictException
import com.mundiapolis.library.circulation.application.model.MemberAccessDeniedException
import com.mundiapolis.library.circulation.application.model.MemberEligibilityUnavailableException
import com.mundiapolis.library.circulation.application.model.MemberNotEligibleException
import com.mundiapolis.library.circulation.application.model.MissingMembershipClaimException
import com.mundiapolis.library.circulation.application.model.NoAvailableCopyException
import com.mundiapolis.library.circulation.application.model.OpenLoanAlreadyExistsException
import com.mundiapolis.library.circulation.application.model.OpenReservationBlocksLoanException
import com.mundiapolis.library.circulation.application.model.PendingReservationBlocksRenewalException
import com.mundiapolis.library.circulation.application.model.RenewalLimitReachedException
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.CancelLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.CancelLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RejectLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RejectLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanUseCase
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.application.port.outbound.CirculationPolicyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.LoanStore
import com.mundiapolis.library.circulation.application.port.outbound.MemberEligibilityStore
import com.mundiapolis.library.circulation.application.port.outbound.OutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.ReservationStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

class CirculationCommandService(
    private val transactionRunner: TransactionRunner,
    private val loanStore: LoanStore,
    private val copyStore: CopyStore,
    private val eligibilityStore: MemberEligibilityStore,
    private val idempotencyStore: IdempotencyStore,
    private val outboxEventStore: OutboxEventStore,
    private val timeProvider: TimeProvider,
    private val identifierGenerator: IdentifierGenerator,
    private val policyStore: CirculationPolicyStore,
    private val reservationStore: ReservationStore,
    private val reservationQueueService: ReservationQueueService,
    private val idempotencyRetention: Duration,
) : RequestLoanUseCase,
    ApproveLoanUseCase,
    RejectLoanUseCase,
    CancelLoanUseCase,
    RenewLoanUseCase,
    ReturnLoanUseCase {
    override fun request(command: RequestLoanCommand): CommandExecution {
        authorizeRequestedMember(command)
        val operation = CommandOperation.REQUEST_LOAN
        val fingerprint = fingerprint(
            operation,
            command.memberId.value.toString(),
            command.editionId.value.toString(),
        )

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            requireEligible(command.memberId)
            reservationStore.lockEdition(command.editionId)
            if (reservationStore.hasOpenForMemberEdition(command.memberId, command.editionId)) {
                throw OpenReservationBlocksLoanException()
            }
            val loan = Loan.request(
                id = LoanId(identifierGenerator.next()),
                memberId = command.memberId,
                editionId = command.editionId,
                requestedAt = now,
            )
            if (!loanStore.create(loan, now)) {
                throw OpenLoanAlreadyExistsException()
            }
            loan
        }
    }

    override fun approve(command: ApproveLoanCommand): CommandExecution {
        val operation = CommandOperation.APPROVE_LOAN
        val fingerprint = fingerprint(operation, command.loanId.value.toString())

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val requested = requireLoan(command.loanId)
            if (requested.status != LoanStatus.REQUESTED) {
                throw LoanStateConflictException(requested.id, requested.status)
            }
            requireEligible(requested.memberId)

            reservationStore.lockEdition(requested.editionId)
            val policy = policyStore.current()

            val copyId = copyStore.allocateAvailable(requested.editionId, now)
                ?: throw NoAvailableCopyException(requested.editionId)
            val approved = requested.approve(copyId, now, now.plus(policy.defaultLoanPeriod))

            if (!loanStore.update(approved, requested.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            approved
        }
    }

    override fun reject(command: RejectLoanCommand): CommandExecution {
        val operation = CommandOperation.REJECT_LOAN
        val fingerprint = fingerprint(operation, command.loanId.value.toString())

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val requested = requireLoan(command.loanId)
            if (requested.status != LoanStatus.REQUESTED) {
                throw LoanStateConflictException(requested.id, requested.status)
            }

            val rejected = requested.reject(now)
            if (!loanStore.update(rejected, requested.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            rejected
        }
    }

    override fun cancel(command: CancelLoanCommand): CommandExecution {
        val operation = CommandOperation.CANCEL_LOAN
        val fingerprint = fingerprint(
            operation,
            command.loanId.value.toString(),
            authorizationBinding(command.principal),
        )

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val requested = requireLoan(command.loanId)
            authorizeLoanMember(requested, command.principal)
            if (requested.status != LoanStatus.REQUESTED) {
                throw LoanStateConflictException(requested.id, requested.status)
            }

            val cancelled = requested.cancel()
            if (!loanStore.update(cancelled, requested.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            cancelled
        }
    }

    override fun returnLoan(command: ReturnLoanCommand): CommandExecution {
        val operation = CommandOperation.RETURN_LOAN
        val fingerprint = fingerprint(operation, command.loanId.value.toString())

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val active = requireLoan(command.loanId)
            if (active.status != LoanStatus.ACTIVE) {
                throw LoanStateConflictException(active.id, active.status)
            }

            val returned = active.returnAt(now)
            if (!loanStore.update(returned, active.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            reservationQueueService.releaseReturnedCopy(
                active.editionId,
                requireNotNull(active.copyId),
                now,
                command.principal.idempotencyOwner.fingerprint,
            )
            returned
        }
    }

    override fun renew(command: RenewLoanCommand): CommandExecution {
        val operation = CommandOperation.RENEW_LOAN
        val fingerprint = fingerprint(
            operation,
            command.loanId.value.toString(),
            authorizationBinding(command.principal),
        )

        return executeIdempotently(
            command.principal.idempotencyOwner,
            command.idempotencyKey,
            operation,
            fingerprint,
        ) { now ->
            val active = requireLoan(command.loanId)
            authorizeLoanMember(active, command.principal)
            if (active.status != LoanStatus.ACTIVE) {
                throw LoanStateConflictException(active.id, active.status)
            }
            requireEligible(active.memberId)
            reservationStore.lockEdition(active.editionId)
            if (reservationStore.hasWaitingForEditionExcluding(active.editionId, active.memberId)) {
                throw PendingReservationBlocksRenewalException()
            }
            val policy = policyStore.current()
            if (now > requireNotNull(active.dueAt)) {
                throw LoanOverdueException(active.id)
            }
            if (active.renewalCount >= policy.maximumRenewals) {
                throw RenewalLimitReachedException(active.id)
            }

            val renewed = active.renew(now, policy.renewalPeriod, policy.maximumRenewals)
            if (!loanStore.update(renewed, active.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            renewed
        }
    }

    private fun requireLoan(id: LoanId): Loan =
        loanStore.lockById(id) ?: throw LoanNotFoundException(id)

    private fun requireEligible(memberId: MemberId) {
        eligibilityStore.lockMember(memberId)
        val eligibility = eligibilityStore.find(memberId)
            ?: throw MemberEligibilityUnavailableException(memberId)
        if (!eligibility.isEligible) {
            throw MemberNotEligibleException(memberId, eligibility.status)
        }
    }

    private fun authorizeRequestedMember(command: RequestLoanCommand) {
        if (command.principal.canActOnBehalf) {
            return
        }
        val authenticatedMembership = command.principal.membershipId
            ?: throw MissingMembershipClaimException()
        if (authenticatedMembership != command.memberId) {
            throw MemberAccessDeniedException()
        }
    }

    private fun authorizeLoanMember(loan: Loan, principal: CommandPrincipal) {
        if (principal.canActOnBehalf) {
            return
        }
        val authenticatedMembership = principal.membershipId
            ?: throw MissingMembershipClaimException()
        if (authenticatedMembership != loan.memberId) {
            // A self-service caller must not be able to distinguish another
            // member's loan identifier from one that does not exist.
            throw LoanNotFoundException(loan.id)
        }
    }

    private fun authorizationBinding(principal: CommandPrincipal): String =
        if (principal.canActOnBehalf) {
            "on-behalf"
        } else {
            "member:${principal.membershipId?.value ?: "missing"}"
        }

    private fun executeIdempotently(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        requestFingerprint: String,
        action: (Instant) -> Loan,
    ): CommandExecution = transactionRunner.required {
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val claimed = idempotencyStore.claim(
            owner = owner,
            key = key,
            operation = operation,
            requestFingerprint = requestFingerprint,
            createdAt = now,
            expiresAt = now.plus(idempotencyRetention),
        )

        if (!claimed) {
            val stored = idempotencyStore.find(owner, key)
                ?: throw IncompleteIdempotencyRecordException()
            if (stored.operation != operation || stored.requestFingerprint != requestFingerprint) {
                throw IdempotencyKeyConflictException()
            }
            return@required CommandExecution(
                result = stored.result ?: throw IncompleteIdempotencyRecordException(),
                replayed = true,
            )
        }

        val result = LoanCommandResult.from(action(now))
        outboxEventStore.append(
            CirculationOutboxEvent(
                id = identifierGenerator.next(),
                aggregateId = result.loanId,
                aggregateVersion = result.version,
                eventType = operation.eventType,
                eventVersion = 1,
                occurredAt = now,
                result = result,
                actorFingerprint = owner.fingerprint,
            ),
        )
        idempotencyStore.complete(owner, key, operation, result, now)

        CommandExecution(result = result, replayed = false)
    }

    private fun fingerprint(operation: CommandOperation, vararg values: String): String {
        val canonical = buildString {
            append("circulation-command-v1")
            append('\u001f')
            append(operation.name)
            values.forEach {
                append('\u001f')
                append(it)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
