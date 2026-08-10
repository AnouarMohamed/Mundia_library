package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException
import com.mundiapolis.library.circulation.application.model.IncompleteIdempotencyRecordException
import com.mundiapolis.library.circulation.application.model.MemberAccessDeniedException
import com.mundiapolis.library.circulation.application.model.MemberEligibilityUnavailableException
import com.mundiapolis.library.circulation.application.model.MemberNotEligibleException
import com.mundiapolis.library.circulation.application.model.MissingMembershipClaimException
import com.mundiapolis.library.circulation.application.model.OpenLoanBlocksReservationException
import com.mundiapolis.library.circulation.application.model.OpenReservationAlreadyExistsException
import com.mundiapolis.library.circulation.application.model.ReservationCommandExecution
import com.mundiapolis.library.circulation.application.model.ReservationCommandResult
import com.mundiapolis.library.circulation.application.model.ReservationHoldExpiredException
import com.mundiapolis.library.circulation.application.model.ReservationHoldNotExpiredException
import com.mundiapolis.library.circulation.application.model.ReservationLimitReachedException
import com.mundiapolis.library.circulation.application.model.ReservationNotFoundException
import com.mundiapolis.library.circulation.application.model.ReservationOperation
import com.mundiapolis.library.circulation.application.model.ReservationOutboxEvent
import com.mundiapolis.library.circulation.application.model.ReservationStateConflictException
import com.mundiapolis.library.circulation.application.model.CirculationOutboxEvent
import com.mundiapolis.library.circulation.application.model.LoanCommandResult
import com.mundiapolis.library.circulation.application.port.inbound.CancelReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.CancelReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ExpireReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.ExpireReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.FulfillReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.FulfillReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.PlaceReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.PlaceReservationUseCase
import com.mundiapolis.library.circulation.application.port.outbound.CirculationPolicyStore
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.LoanStore
import com.mundiapolis.library.circulation.application.port.outbound.MemberEligibilityStore
import com.mundiapolis.library.circulation.application.port.outbound.OutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.ReservationIdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.ReservationOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.ReservationStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.Reservation
import com.mundiapolis.library.circulation.domain.model.ReservationId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

class ReservationCommandService(
    private val transactionRunner: TransactionRunner,
    private val reservationStore: ReservationStore,
    private val loanStore: LoanStore,
    private val copyStore: CopyStore,
    private val eligibilityStore: MemberEligibilityStore,
    private val policyStore: CirculationPolicyStore,
    private val idempotencyStore: ReservationIdempotencyStore,
    private val reservationOutboxEventStore: ReservationOutboxEventStore,
    private val loanOutboxEventStore: OutboxEventStore,
    private val queueService: ReservationQueueService,
    private val timeProvider: TimeProvider,
    private val identifierGenerator: IdentifierGenerator,
    private val idempotencyRetention: Duration,
) : PlaceReservationUseCase,
    CancelReservationUseCase,
    FulfillReservationUseCase,
    ExpireReservationUseCase {
    override fun place(command: PlaceReservationCommand): ReservationCommandExecution {
        authorizeRequestedMember(command.memberId, command.principal)
        return executeIdempotently(
            command.principal,
            command.idempotencyKey,
            ReservationOperation.PLACE,
            fingerprint("PLACE", command.memberId.value.toString(), command.editionId.value.toString()),
        ) { now ->
            requireEligible(command.memberId)
            reservationStore.lockEdition(command.editionId)
            val policy = policyStore.current()
            if (loanStore.hasOpenForMemberEdition(command.memberId, command.editionId)) {
                throw OpenLoanBlocksReservationException()
            }
            if (reservationStore.hasOpenForMemberEdition(command.memberId, command.editionId)) {
                throw OpenReservationAlreadyExistsException()
            }
            if (reservationStore.countOpenForMember(command.memberId) >= policy.maximumActiveReservations) {
                throw ReservationLimitReachedException(policy.maximumActiveReservations)
            }

            val olderWaiting = reservationStore.lockOldestWaiting(command.editionId)
            val waiting = Reservation.place(
                ReservationId(identifierGenerator.next()),
                command.memberId,
                command.editionId,
                now,
            )
            if (!reservationStore.create(waiting, now)) {
                throw OpenReservationAlreadyExistsException()
            }
            if (olderWaiting != null) {
                waiting
            } else {
                val copyId = copyStore.reserveAvailable(command.editionId, now)
                if (copyId == null) {
                    waiting
                } else {
                    val ready = waiting.makeReady(copyId, now, policy.reservationHoldPeriod)
                    if (!reservationStore.update(ready, waiting.version, now)) {
                        throw ConcurrentCirculationUpdateException()
                    }
                    ready
                }
            }
        }
    }

    override fun cancel(command: CancelReservationCommand): ReservationCommandExecution =
        executeIdempotently(
            command.principal,
            command.idempotencyKey,
            ReservationOperation.CANCEL,
            fingerprint(
                "CANCEL",
                command.reservationId.value.toString(),
                authorizationBinding(command.principal),
            ),
        ) { now ->
            val open = lockReservationInCanonicalOrder(
                command.reservationId,
                command.principal,
                requireEligibility = false,
            )
            if (open.status !in setOf(ReservationStatus.WAITING, ReservationStatus.READY)) {
                throw ReservationStateConflictException(open.id, open.status)
            }
            val cancelled = open.cancel(now)
            if (!reservationStore.update(cancelled, open.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            open.copyId?.let {
                queueService.reassignOrReleaseReservedCopy(
                    open.editionId,
                    it,
                    now,
                    command.principal.idempotencyOwner.fingerprint,
                )
            }
            cancelled
        }

    override fun fulfill(command: FulfillReservationCommand): ReservationCommandExecution =
        executeIdempotently(
            command.principal,
            command.idempotencyKey,
            ReservationOperation.FULFILL,
            fingerprint(
                "FULFILL",
                command.reservationId.value.toString(),
                authorizationBinding(command.principal),
            ),
        ) { now ->
            val ready = lockReservationInCanonicalOrder(
                command.reservationId,
                command.principal,
                requireEligibility = true,
            )
            if (ready.status != ReservationStatus.READY) {
                throw ReservationStateConflictException(ready.id, ready.status)
            }
            if (now > requireNotNull(ready.expiresAt)) {
                throw ReservationHoldExpiredException(ready.id)
            }
            val policy = policyStore.current()
            val copyId = requireNotNull(ready.copyId)
            if (!copyStore.reservedToLoan(copyId, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            val loan = Loan.request(
                LoanId(identifierGenerator.next()),
                ready.memberId,
                ready.editionId,
                now,
            ).approve(copyId, now, now.plus(policy.defaultLoanPeriod))
            if (!loanStore.create(loan, now)) {
                throw OpenLoanBlocksReservationException()
            }
            val fulfilled = ready.fulfill(now)
            if (!reservationStore.update(fulfilled, ready.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            loanOutboxEventStore.append(
                CirculationOutboxEvent(
                    id = identifierGenerator.next(),
                    aggregateId = loan.id,
                    aggregateVersion = loan.version,
                    eventType = "circulation.loan.approved",
                    eventVersion = 1,
                    occurredAt = now,
                    result = LoanCommandResult.from(loan),
                    actorFingerprint = command.principal.idempotencyOwner.fingerprint,
                ),
            )
            fulfilled
        }

    override fun expire(command: ExpireReservationCommand): ReservationCommandExecution =
        executeIdempotently(
            command.principal,
            command.idempotencyKey,
            ReservationOperation.EXPIRE,
            fingerprint(
                "EXPIRE",
                command.reservationId.value.toString(),
                authorizationBinding(command.principal),
            ),
        ) { now ->
            val ready = lockReservationInCanonicalOrder(
                command.reservationId,
                command.principal,
                requireEligibility = false,
            )
            if (ready.status != ReservationStatus.READY) {
                throw ReservationStateConflictException(ready.id, ready.status)
            }
            if (now < requireNotNull(ready.expiresAt)) {
                throw ReservationHoldNotExpiredException(ready.id)
            }
            val expired = ready.expire(now)
            if (!reservationStore.update(expired, ready.version, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            queueService.reassignOrReleaseReservedCopy(
                ready.editionId,
                requireNotNull(ready.copyId),
                now,
                command.principal.idempotencyOwner.fingerprint,
            )
            expired
        }

    private fun executeIdempotently(
        principal: CommandPrincipal,
        key: IdempotencyKey,
        operation: ReservationOperation,
        requestFingerprint: String,
        action: (Instant) -> Reservation,
    ): ReservationCommandExecution = transactionRunner.required {
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val owner = principal.idempotencyOwner
        val claimed = idempotencyStore.claim(
            owner,
            key,
            operation,
            requestFingerprint,
            now,
            now.plus(idempotencyRetention),
        )
        if (!claimed) {
            val stored = idempotencyStore.find(owner, key)
                ?: throw IncompleteIdempotencyRecordException()
            if (stored.operation != operation || stored.requestFingerprint != requestFingerprint) {
                throw IdempotencyKeyConflictException()
            }
            return@required ReservationCommandExecution(
                stored.result ?: throw IncompleteIdempotencyRecordException(),
                replayed = true,
            )
        }

        val result = ReservationCommandResult.from(action(now))
        reservationOutboxEventStore.append(
            ReservationOutboxEvent(
                identifierGenerator.next(),
                result,
                operation.eventType,
                now,
                owner.fingerprint,
            ),
        )
        idempotencyStore.complete(owner, key, operation, result, now)
        ReservationCommandExecution(result, replayed = false)
    }

    private fun requireReservation(id: ReservationId): Reservation =
        reservationStore.lockById(id) ?: throw ReservationNotFoundException(id)

    private fun lockReservationInCanonicalOrder(
        id: ReservationId,
        principal: CommandPrincipal,
        requireEligibility: Boolean,
    ): Reservation {
        val observed = reservationStore.findById(id) ?: throw ReservationNotFoundException(id)
        authorizeReservationMember(observed.memberId, observed.id, principal)
        // Canonical order for shared circulation resources: member (when
        // required) -> edition -> reservation row -> copy row.
        if (requireEligibility) requireEligible(observed.memberId)
        reservationStore.lockEdition(observed.editionId)
        val locked = requireReservation(id)
        if (
            locked.memberId != observed.memberId ||
            locked.editionId != observed.editionId
        ) {
            throw ConcurrentCirculationUpdateException()
        }
        authorizeReservationMember(locked.memberId, locked.id, principal)
        return locked
    }

    private fun requireEligible(memberId: MemberId) {
        eligibilityStore.lockMember(memberId)
        val eligibility = eligibilityStore.find(memberId)
            ?: throw MemberEligibilityUnavailableException(memberId)
        if (!eligibility.isEligible) {
            throw MemberNotEligibleException(memberId, eligibility.status)
        }
    }

    private fun authorizeRequestedMember(memberId: MemberId, principal: CommandPrincipal) {
        if (principal.canActOnBehalf) return
        val authenticated = principal.membershipId ?: throw MissingMembershipClaimException()
        if (authenticated != memberId) throw MemberAccessDeniedException()
    }

    private fun authorizeReservationMember(
        memberId: MemberId,
        reservationId: ReservationId,
        principal: CommandPrincipal,
    ) {
        if (principal.canActOnBehalf) return
        val authenticated = principal.membershipId ?: throw MissingMembershipClaimException()
        if (authenticated != memberId) throw ReservationNotFoundException(reservationId)
    }

    private fun authorizationBinding(principal: CommandPrincipal): String =
        if (principal.canActOnBehalf) "on-behalf" else "member:${principal.membershipId?.value}"

    private fun fingerprint(vararg values: String): String {
        val canonical = listOf("reservation-command-v1", *values).joinToString("\u001f")
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
