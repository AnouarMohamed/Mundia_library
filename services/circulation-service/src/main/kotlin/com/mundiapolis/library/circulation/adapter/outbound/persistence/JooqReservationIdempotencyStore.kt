package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_RESERVATION_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationReservationIdempotencyRecord
import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.ReservationCommandResult
import com.mundiapolis.library.circulation.application.model.ReservationOperation
import com.mundiapolis.library.circulation.application.model.StoredReservationIdempotencyResult
import com.mundiapolis.library.circulation.application.port.outbound.ReservationIdempotencyStore
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.ReservationId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqReservationIdempotencyStore(
    private val dsl: DSLContext,
) : ReservationIdempotencyStore {
    override fun claim(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: ReservationOperation,
        requestFingerprint: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): Boolean = dsl.insertInto(CIRCULATION_RESERVATION_IDEMPOTENCY)
        .set(CIRCULATION_RESERVATION_IDEMPOTENCY.OWNER_FINGERPRINT, owner.fingerprint)
        .set(CIRCULATION_RESERVATION_IDEMPOTENCY.IDEMPOTENCY_KEY, key.value)
        .set(CIRCULATION_RESERVATION_IDEMPOTENCY.OPERATION, operation.name)
        .set(CIRCULATION_RESERVATION_IDEMPOTENCY.REQUEST_FINGERPRINT, requestFingerprint)
        .set(CIRCULATION_RESERVATION_IDEMPOTENCY.CREATED_AT, createdAt.toOffsetDateTime())
        .set(CIRCULATION_RESERVATION_IDEMPOTENCY.EXPIRES_AT, expiresAt.toOffsetDateTime())
        .onConflictDoNothing()
        .execute() == 1

    override fun find(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
    ): StoredReservationIdempotencyResult? = dsl.selectFrom(CIRCULATION_RESERVATION_IDEMPOTENCY)
        .where(
            CIRCULATION_RESERVATION_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                .and(CIRCULATION_RESERVATION_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value)),
        )
        .fetchOne()
        ?.toStoredResult()

    override fun complete(
        owner: IdempotencyOwner,
        key: IdempotencyKey,
        operation: ReservationOperation,
        result: ReservationCommandResult,
        completedAt: Instant,
    ) {
        val updated = dsl.update(CIRCULATION_RESERVATION_IDEMPOTENCY)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.RESPONSE_STATUS, operation.responseStatus)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.RESERVATION_ID, result.reservationId.value)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.MEMBER_ID, result.memberId.value)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.EDITION_ID, result.editionId.value)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.COPY_ID, result.copyId?.value)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.RESERVATION_STATUS, result.status.name)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.PLACED_AT, result.placedAt.toOffsetDateTime())
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.READY_AT, result.readyAt?.toOffsetDateTime())
            .set(
                CIRCULATION_RESERVATION_IDEMPOTENCY.EXPIRES_AT_RESULT,
                result.expiresAt?.toOffsetDateTime(),
            )
            .set(
                CIRCULATION_RESERVATION_IDEMPOTENCY.FULFILLED_AT,
                result.fulfilledAt?.toOffsetDateTime(),
            )
            .set(
                CIRCULATION_RESERVATION_IDEMPOTENCY.CANCELLED_AT,
                result.cancelledAt?.toOffsetDateTime(),
            )
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.RESERVATION_VERSION, result.version)
            .set(CIRCULATION_RESERVATION_IDEMPOTENCY.COMPLETED_AT, completedAt.toOffsetDateTime())
            .where(
                CIRCULATION_RESERVATION_IDEMPOTENCY.OWNER_FINGERPRINT.eq(owner.fingerprint)
                    .and(CIRCULATION_RESERVATION_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key.value))
                    .and(CIRCULATION_RESERVATION_IDEMPOTENCY.OPERATION.eq(operation.name))
                    .and(CIRCULATION_RESERVATION_IDEMPOTENCY.COMPLETED_AT.isNull),
            )
            .execute()
        if (updated != 1) {
            throw ConcurrentCirculationUpdateException()
        }
    }

    private fun CirculationReservationIdempotencyRecord.toStoredResult():
        StoredReservationIdempotencyResult = StoredReservationIdempotencyResult(
        operation = ReservationOperation.valueOf(requireNotNull(operation)),
        requestFingerprint = requireNotNull(requestFingerprint),
        result = if (completedAt == null) {
            null
        } else {
            ReservationCommandResult(
                reservationId = ReservationId(requireNotNull(reservationId)),
                memberId = MemberId(requireNotNull(memberId)),
                editionId = EditionId(requireNotNull(editionId)),
                copyId = copyId?.let(::CopyId),
                status = ReservationStatus.valueOf(requireNotNull(reservationStatus)),
                placedAt = requireNotNull(placedAt).toInstant(),
                readyAt = readyAt?.toInstant(),
                expiresAt = expiresAtResult?.toInstant(),
                fulfilledAt = fulfilledAt?.toInstant(),
                cancelledAt = cancelledAt?.toInstant(),
                version = requireNotNull(reservationVersion),
            )
        },
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
