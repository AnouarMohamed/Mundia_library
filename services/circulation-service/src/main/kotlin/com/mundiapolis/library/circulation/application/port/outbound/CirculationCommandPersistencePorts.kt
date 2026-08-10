package com.mundiapolis.library.circulation.application.port.outbound

import com.mundiapolis.library.circulation.application.model.BrokerPublishAcknowledgement
import com.mundiapolis.library.circulation.application.model.CirculationOutboxEvent
import com.mundiapolis.library.circulation.application.model.ClaimedOutboxEvent
import com.mundiapolis.library.circulation.application.model.CommandOperation
import com.mundiapolis.library.circulation.application.model.EncodedOutboxEvent
import com.mundiapolis.library.circulation.application.model.FineCommandResult
import com.mundiapolis.library.circulation.application.model.FineOutboxEvent
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.InventoryCommandResult
import com.mundiapolis.library.circulation.application.model.InventoryAuditEntry
import com.mundiapolis.library.circulation.application.model.InventoryOperation
import com.mundiapolis.library.circulation.application.model.InventoryOutboxEvent
import com.mundiapolis.library.circulation.application.model.LoanCommandResult
import com.mundiapolis.library.circulation.application.model.OutboxDeliveryStatistics
import com.mundiapolis.library.circulation.application.model.OutboxFailureCode
import com.mundiapolis.library.circulation.application.model.OutboxFailureDisposition
import com.mundiapolis.library.circulation.application.model.PolicyOutboxEvent
import com.mundiapolis.library.circulation.application.model.ReservationOperation
import com.mundiapolis.library.circulation.application.model.ReservationCommandResult
import com.mundiapolis.library.circulation.application.model.ReservationOutboxEvent
import com.mundiapolis.library.circulation.application.model.RateLimitDecision
import com.mundiapolis.library.circulation.application.model.StoredPolicyIdempotencyResult
import com.mundiapolis.library.circulation.application.model.StoredReservationIdempotencyResult
import com.mundiapolis.library.circulation.application.model.StoredFineIdempotencyResult
import com.mundiapolis.library.circulation.application.model.StoredIdempotencyResult
import com.mundiapolis.library.circulation.application.model.StoredInventoryIdempotencyResult
import com.mundiapolis.library.circulation.domain.model.Copy
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.Fine
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntry
import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.CirculationPolicy
import com.mundiapolis.library.circulation.domain.model.Reservation
import com.mundiapolis.library.circulation.domain.model.ReservationId
import java.time.Instant
import java.util.UUID

interface TransactionRunner {
    fun <T : Any> required(block: () -> T): T
}

interface LoanStore {
    fun create(loan: Loan, now: Instant): Boolean

    fun lockById(id: LoanId): Loan?

    fun update(loan: Loan, expectedVersion: Long, now: Instant): Boolean

    fun hasOpenForMemberEdition(memberId: MemberId, editionId: EditionId): Boolean
}

interface CopyStore {
    fun create(copy: Copy, now: Instant): Boolean

    fun findById(id: CopyId): Copy?

    fun lockById(id: CopyId): Copy?

    fun update(copy: Copy, expectedVersion: Long, now: Instant): Boolean

    fun allocateAvailable(editionId: EditionId, now: Instant): CopyId?

    fun release(copyId: CopyId, now: Instant): Boolean

    fun reserveAvailable(editionId: EditionId, now: Instant): CopyId?

    fun reservedToLoan(copyId: CopyId, now: Instant): Boolean

    fun releaseReserved(copyId: CopyId, now: Instant): Boolean

    fun returnToReservation(copyId: CopyId, now: Instant): Boolean

    fun reserve(copyId: CopyId, now: Instant): Boolean
}

interface ReservationStore {
    fun lockEdition(editionId: EditionId)

    fun create(reservation: Reservation, now: Instant): Boolean

    fun findById(id: ReservationId): Reservation?

    fun lockById(id: ReservationId): Reservation?

    fun update(reservation: Reservation, expectedVersion: Long, now: Instant): Boolean

    fun lockOldestWaiting(editionId: EditionId): Reservation?

    fun hasOpenForMemberEdition(memberId: MemberId, editionId: EditionId): Boolean

    fun countOpenForMember(memberId: MemberId): Int

    fun hasWaitingForEditionExcluding(editionId: EditionId, memberId: MemberId): Boolean

    fun findExpiredIds(now: Instant, batchSize: Int): List<ReservationId>
}

interface ReservationIdempotencyStore {
    fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: ReservationOperation,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean

    fun find(owner: IdempotencyOwner, key: IdempotencyKey): StoredReservationIdempotencyResult?

    fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: ReservationOperation,
        result: ReservationCommandResult,
        completedAt: Instant,
    )
}

interface CirculationPolicyStore {
    fun current(): CirculationPolicy

    fun lockCurrent(): CirculationPolicy

    fun findRevision(revisionId: UUID): CirculationPolicy?

    fun install(policy: CirculationPolicy, expectedRevisionId: UUID): Boolean
}

interface PolicyIdempotencyStore {
    fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean

    fun find(owner: IdempotencyOwner, key: IdempotencyKey): StoredPolicyIdempotencyResult?

    fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        revisionId: UUID,
        completedAt: Instant,
    )
}

fun interface RateLimitStore {
    fun consume(
        principalFingerprint: String,
        bucketKey: String,
        limit: Int,
        window: java.time.Duration,
        now: Instant,
    ): RateLimitDecision
}

fun interface RateLimitMaintenanceStore {
    fun deleteExpired(cutoff: Instant, batchSize: Int): Int
}

interface InventoryIdempotencyStore {
    fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: InventoryOperation,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean

    fun find(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
    ): StoredInventoryIdempotencyResult?

    fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: InventoryOperation,
        result: InventoryCommandResult,
        completedAt: Instant,
    )
}

interface FineStore {
    fun create(fine: Fine, now: Instant): Boolean

    fun lockById(id: FineId): Fine?

    fun update(fine: Fine, expectedVersion: Long, now: Instant): Boolean
}

fun interface FineLedgerStore {
    fun append(entry: FineLedgerEntry): Boolean
}

interface IdempotencyStore {
    fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean

    fun find(owner: IdempotencyOwner, key: IdempotencyKey): StoredIdempotencyResult?

    fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        result: LoanCommandResult,
        completedAt: Instant,
    )
}

interface FineIdempotencyStore {
    fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean

    fun find(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
    ): StoredFineIdempotencyResult?

    fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        result: FineCommandResult,
        completedAt: Instant,
    )
}

fun interface OutboxEventStore {
    fun append(event: CirculationOutboxEvent)
}

fun interface FineOutboxEventStore {
    fun append(event: FineOutboxEvent)
}

fun interface InventoryOutboxEventStore {
    fun append(event: InventoryOutboxEvent)
}

fun interface ReservationOutboxEventStore {
    fun append(event: ReservationOutboxEvent)
}

fun interface PolicyOutboxEventStore {
    fun append(event: PolicyOutboxEvent)
}

fun interface InventoryAuditStore {
    fun append(entry: InventoryAuditEntry)
}

interface OutboxDeliveryStore {
    fun claimBatch(
        owner: String,
        now: Instant,
        leaseExpiresAt: Instant,
        batchSize: Int,
    ): List<ClaimedOutboxEvent>

    fun markPublished(
        owner: String,
        event: ClaimedOutboxEvent,
        acknowledgement: BrokerPublishAcknowledgement,
        publishedAt: Instant,
    ): Boolean

    fun recordFailure(
        owner: String,
        event: ClaimedOutboxEvent,
        code: OutboxFailureCode,
        failedAt: Instant,
        nextAttemptAt: Instant,
        maximumAttempts: Int,
        blockImmediately: Boolean,
    ): OutboxFailureDisposition

    fun deletePublishedBefore(cutoff: Instant, batchSize: Int): Int

    fun statistics(now: Instant): OutboxDeliveryStatistics
}

fun interface EventContractEncoder {
    fun encode(event: ClaimedOutboxEvent): EncodedOutboxEvent
}

fun interface BrokerEventPublisher {
    fun publish(event: EncodedOutboxEvent): BrokerPublishAcknowledgement
}

fun interface TimeProvider {
    fun now(): Instant
}

fun interface IdentifierGenerator {
    fun next(): UUID
}
