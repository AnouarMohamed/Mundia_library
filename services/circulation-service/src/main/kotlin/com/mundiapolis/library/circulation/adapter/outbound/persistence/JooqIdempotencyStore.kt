package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationIdempotencyRecord
import com.mundiapolis.library.circulation.application.model.CommandOperation
import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.LoanCommandResult
import com.mundiapolis.library.circulation.application.model.StoredIdempotencyResult
import com.mundiapolis.library.circulation.application.port.outbound.IdempotencyStore
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqIdempotencyStore(
    private val dsl: DSLContext,
) : IdempotencyStore {
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
    ): StoredIdempotencyResult? =
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
        result: LoanCommandResult,
        completedAt: Instant,
    ) {
        val updated = dsl.update(CIRCULATION_IDEMPOTENCY)
            .set(CIRCULATION_IDEMPOTENCY.RESPONSE_STATUS, operation.responseStatus)
            .set(CIRCULATION_IDEMPOTENCY.LOAN_ID, result.loanId.value)
            .set(CIRCULATION_IDEMPOTENCY.MEMBER_ID, result.memberId.value)
            .set(CIRCULATION_IDEMPOTENCY.EDITION_ID, result.editionId.value)
            .set(CIRCULATION_IDEMPOTENCY.COPY_ID, result.copyId?.value)
            .set(CIRCULATION_IDEMPOTENCY.LOAN_STATUS, result.status.name)
            .set(CIRCULATION_IDEMPOTENCY.REQUESTED_AT, result.requestedAt.toOffsetDateTime())
            .set(
                CIRCULATION_IDEMPOTENCY.CHECKED_OUT_AT,
                result.checkedOutAt?.toOffsetDateTime(),
            )
            .set(CIRCULATION_IDEMPOTENCY.DUE_AT, result.dueAt?.toOffsetDateTime())
            .set(CIRCULATION_IDEMPOTENCY.RETURNED_AT, result.returnedAt?.toOffsetDateTime())
            .set(CIRCULATION_IDEMPOTENCY.LOAN_VERSION, result.version)
            .set(CIRCULATION_IDEMPOTENCY.RENEWAL_COUNT, result.renewalCount)
            .set(CIRCULATION_IDEMPOTENCY.COMPLETED_AT, completedAt.toOffsetDateTime())
            .where(
                CIRCULATION_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                    .and(CIRCULATION_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value))
                    .and(CIRCULATION_IDEMPOTENCY.OPERATION.eq(operation.name))
                    .and(CIRCULATION_IDEMPOTENCY.COMPLETED_AT.isNull),
            )
            .execute()
        if (updated != 1) {
            throw ConcurrentCirculationUpdateException()
        }
    }

    private fun CirculationIdempotencyRecord.toStoredResult(): StoredIdempotencyResult {
        val storedOperation = CommandOperation.valueOf(requireNotNull(operation))
        val storedResult = if (completedAt == null || !storedOperation.isLoanOperation) {
            null
        } else {
            check(responseStatus == storedOperation.responseStatus) {
                "Stored idempotency response status does not match its operation"
            }
            LoanCommandResult(
                loanId = LoanId(requireNotNull(loanId)),
                memberId = MemberId(requireNotNull(memberId)),
                editionId = EditionId(requireNotNull(editionId)),
                copyId = copyId?.let(::CopyId),
                status = LoanStatus.valueOf(requireNotNull(loanStatus)),
                requestedAt = requireNotNull(requestedAt).toInstant(),
                checkedOutAt = checkedOutAt?.toInstant(),
                dueAt = dueAt?.toInstant(),
                returnedAt = returnedAt?.toInstant(),
                // V3 records predate the renewal field. Their only valid historical
                // value is zero; V4+ completions persist the explicit count.
                renewalCount = renewalCount ?: 0,
                version = requireNotNull(loanVersion),
            )
        }

        return StoredIdempotencyResult(
            operation = storedOperation,
            requestFingerprint = requireNotNull(requestFingerprint),
            result = storedResult,
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
