package com.mundiapolis.library.circulation.application.port.outbound

import com.mundiapolis.library.circulation.application.model.CirculationOutboxEvent
import com.mundiapolis.library.circulation.application.model.CommandOperation
import com.mundiapolis.library.circulation.application.model.FineCommandResult
import com.mundiapolis.library.circulation.application.model.FineOutboxEvent
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.LoanCommandResult
import com.mundiapolis.library.circulation.application.model.StoredIdempotencyResult
import com.mundiapolis.library.circulation.application.model.StoredFineIdempotencyResult
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.Fine
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntry
import com.mundiapolis.library.circulation.domain.model.Loan
import com.mundiapolis.library.circulation.domain.model.LoanId
import java.time.Instant
import java.util.UUID

interface TransactionRunner {
    fun <T : Any> required(block: () -> T): T
}

interface LoanStore {
    fun create(loan: Loan, now: Instant): Boolean

    fun lockById(id: LoanId): Loan?

    fun update(loan: Loan, expectedVersion: Long, now: Instant): Boolean
}

interface CopyStore {
    fun allocateAvailable(editionId: EditionId, now: Instant): CopyId?

    fun release(copyId: CopyId, now: Instant): Boolean
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

fun interface TimeProvider {
    fun now(): Instant
}

fun interface IdentifierGenerator {
    fun next(): UUID
}
