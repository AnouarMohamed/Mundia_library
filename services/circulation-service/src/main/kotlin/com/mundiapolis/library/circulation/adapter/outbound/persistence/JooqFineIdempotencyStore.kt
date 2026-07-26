package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationIdempotencyRecord
import com.mundiapolis.library.circulation.application.model.CommandOperation
import com.mundiapolis.library.circulation.application.model.FineCommandResult
import com.mundiapolis.library.circulation.application.model.FinePersistenceConflictException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.StoredFineIdempotencyResult
import com.mundiapolis.library.circulation.application.port.outbound.FineIdempotencyStore
import com.mundiapolis.library.circulation.domain.model.FineId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryType
import com.mundiapolis.library.circulation.domain.model.FineStatus
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqFineIdempotencyStore(
    private val dsl: DSLContext,
) : FineIdempotencyStore {
    override fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean =
        dsl.insertInto(CIRCULATION_IDEMPOTENCY)
            .set(CIRCULATION_IDEMPOTENCY.OWNER_FINGERPRINT, owner.fingerprint)
            .set(CIRCULATION_IDEMPOTENCY.IDEMPOTENCY_KEY, key.value)
            .set(CIRCULATION_IDEMPOTENCY.OPERATION, operation.name)
            .set(CIRCULATION_IDEMPOTENCY.REQUEST_FINGERPRINT, requestFingerprint)
            .set(CIRCULATION_IDEMPOTENCY.CREATED_AT, createdAt.toOffsetDateTime())
            .set(CIRCULATION_IDEMPOTENCY.EXPIRES_AT, expiresAt.toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1

    override fun find(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
    ): StoredFineIdempotencyResult? =
        dsl.selectFrom(CIRCULATION_IDEMPOTENCY)
            .where(
                CIRCULATION_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                    .and(CIRCULATION_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value)),
            )
            .fetchOne()
            ?.toStoredResult()

    override fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: CommandOperation,
        result: FineCommandResult,
        completedAt: Instant,
    ) {
        val updated = dsl.update(CIRCULATION_IDEMPOTENCY)
            .set(CIRCULATION_IDEMPOTENCY.RESPONSE_STATUS, operation.responseStatus)
            .set(CIRCULATION_IDEMPOTENCY.LOAN_ID, result.loanId.value)
            .set(CIRCULATION_IDEMPOTENCY.MEMBER_ID, result.memberId.value)
            .set(CIRCULATION_IDEMPOTENCY.FINE_ID, result.fineId.value)
            .set(CIRCULATION_IDEMPOTENCY.CURRENCY, result.currency)
            .set(CIRCULATION_IDEMPOTENCY.FINE_BALANCE_MINOR, result.balanceMinor)
            .set(CIRCULATION_IDEMPOTENCY.FINE_STATUS, result.status.name)
            .set(CIRCULATION_IDEMPOTENCY.LEDGER_ENTRY_ID, result.ledgerEntryId.value)
            .set(CIRCULATION_IDEMPOTENCY.LEDGER_ENTRY_TYPE, result.ledgerEntryType.name)
            .set(CIRCULATION_IDEMPOTENCY.LEDGER_DELTA_MINOR, result.ledgerDeltaMinor)
            .set(CIRCULATION_IDEMPOTENCY.FINE_VERSION, result.version)
            .set(CIRCULATION_IDEMPOTENCY.COMPLETED_AT, completedAt.toOffsetDateTime())
            .where(
                CIRCULATION_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                    .and(CIRCULATION_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value))
                    .and(CIRCULATION_IDEMPOTENCY.OPERATION.eq(operation.name))
                    .and(CIRCULATION_IDEMPOTENCY.COMPLETED_AT.isNull),
            )
            .execute()
        if (updated != 1) {
            throw FinePersistenceConflictException()
        }
    }

    private fun CirculationIdempotencyRecord.toStoredResult(): StoredFineIdempotencyResult {
        val storedOperation = CommandOperation.valueOf(requireNotNull(operation))
        val storedResult = if (completedAt == null || storedOperation.isLoanOperation) {
            null
        } else {
            check(responseStatus == storedOperation.responseStatus) {
                "Stored idempotency response status does not match its operation"
            }
            FineCommandResult(
                fineId = FineId(requireNotNull(fineId)),
                loanId = LoanId(requireNotNull(loanId)),
                memberId = MemberId(requireNotNull(memberId)),
                currency = requireNotNull(currency),
                balanceMinor = requireNotNull(fineBalanceMinor),
                status = FineStatus.valueOf(requireNotNull(fineStatus)),
                version = requireNotNull(fineVersion),
                ledgerEntryId = FineLedgerEntryId(requireNotNull(ledgerEntryId)),
                ledgerEntryType = FineLedgerEntryType.valueOf(requireNotNull(ledgerEntryType)),
                ledgerDeltaMinor = requireNotNull(ledgerDeltaMinor),
                occurredAt = requireNotNull(completedAt).toInstant(),
            )
        }
        return StoredFineIdempotencyResult(
            operation = storedOperation,
            requestFingerprint = requireNotNull(requestFingerprint),
            result = storedResult,
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
