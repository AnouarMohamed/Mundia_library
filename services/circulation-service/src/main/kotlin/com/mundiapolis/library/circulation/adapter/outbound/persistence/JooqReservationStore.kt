package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_RESERVATION
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationReservationRecord
import com.mundiapolis.library.circulation.application.port.outbound.ReservationStore
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.Reservation
import com.mundiapolis.library.circulation.domain.model.ReservationId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqReservationStore(
    private val dsl: DSLContext,
) : ReservationStore {
    override fun lockEdition(editionId: EditionId) {
        dsl.fetch(
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
            editionId.value.toString(),
        )
    }

    override fun create(reservation: Reservation, now: Instant): Boolean =
        dsl.insertInto(CIRCULATION_RESERVATION)
            .set(CIRCULATION_RESERVATION.ID, reservation.id.value)
            .set(CIRCULATION_RESERVATION.MEMBER_ID, reservation.memberId.value)
            .set(CIRCULATION_RESERVATION.EDITION_ID, reservation.editionId.value)
            .set(CIRCULATION_RESERVATION.COPY_ID, reservation.copyId?.value)
            .set(CIRCULATION_RESERVATION.STATUS, reservation.status.name)
            .set(CIRCULATION_RESERVATION.PLACED_AT, reservation.placedAt.toOffsetDateTime())
            .set(CIRCULATION_RESERVATION.READY_AT, reservation.readyAt?.toOffsetDateTime())
            .set(CIRCULATION_RESERVATION.EXPIRES_AT, reservation.expiresAt?.toOffsetDateTime())
            .set(CIRCULATION_RESERVATION.FULFILLED_AT, reservation.fulfilledAt?.toOffsetDateTime())
            .set(CIRCULATION_RESERVATION.CANCELLED_AT, reservation.cancelledAt?.toOffsetDateTime())
            .set(CIRCULATION_RESERVATION.VERSION, reservation.version)
            .set(CIRCULATION_RESERVATION.CREATED_AT, now.toOffsetDateTime())
            .set(CIRCULATION_RESERVATION.UPDATED_AT, now.toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1

    override fun lockById(id: ReservationId): Reservation? =
        dsl.selectFrom(CIRCULATION_RESERVATION)
            .where(CIRCULATION_RESERVATION.ID.eq(id.value))
            .forUpdate()
            .fetchOne()
            ?.toDomain()

    override fun update(
        reservation: Reservation,
        expectedVersion: Long,
        now: Instant,
    ): Boolean = dsl.update(CIRCULATION_RESERVATION)
        .set(CIRCULATION_RESERVATION.COPY_ID, reservation.copyId?.value)
        .set(CIRCULATION_RESERVATION.STATUS, reservation.status.name)
        .set(CIRCULATION_RESERVATION.READY_AT, reservation.readyAt?.toOffsetDateTime())
        .set(CIRCULATION_RESERVATION.EXPIRES_AT, reservation.expiresAt?.toOffsetDateTime())
        .set(CIRCULATION_RESERVATION.FULFILLED_AT, reservation.fulfilledAt?.toOffsetDateTime())
        .set(CIRCULATION_RESERVATION.CANCELLED_AT, reservation.cancelledAt?.toOffsetDateTime())
        .set(CIRCULATION_RESERVATION.VERSION, reservation.version)
        .set(CIRCULATION_RESERVATION.UPDATED_AT, now.toOffsetDateTime())
        .where(
            CIRCULATION_RESERVATION.ID.eq(reservation.id.value)
                .and(CIRCULATION_RESERVATION.VERSION.eq(expectedVersion)),
        )
        .execute() == 1

    override fun lockOldestWaiting(editionId: EditionId): Reservation? =
        dsl.selectFrom(CIRCULATION_RESERVATION)
            .where(
                CIRCULATION_RESERVATION.EDITION_ID.eq(editionId.value)
                    .and(CIRCULATION_RESERVATION.STATUS.eq(ReservationStatus.WAITING.name)),
            )
            .orderBy(CIRCULATION_RESERVATION.PLACED_AT, CIRCULATION_RESERVATION.ID)
            .limit(1)
            .forUpdate()
            .fetchOne()
            ?.toDomain()

    override fun hasOpenForMemberEdition(memberId: MemberId, editionId: EditionId): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(CIRCULATION_RESERVATION)
                .where(
                    CIRCULATION_RESERVATION.MEMBER_ID.eq(memberId.value)
                        .and(CIRCULATION_RESERVATION.EDITION_ID.eq(editionId.value))
                        .and(CIRCULATION_RESERVATION.STATUS.`in`("WAITING", "READY")),
                ),
        )

    override fun countOpenForMember(memberId: MemberId): Int =
        dsl.fetchCount(
            CIRCULATION_RESERVATION,
            CIRCULATION_RESERVATION.MEMBER_ID.eq(memberId.value)
                .and(CIRCULATION_RESERVATION.STATUS.`in`("WAITING", "READY")),
        )

    override fun hasWaitingForEditionExcluding(
        editionId: EditionId,
        memberId: MemberId,
    ): Boolean = dsl.fetchExists(
        dsl.selectOne()
            .from(CIRCULATION_RESERVATION)
            .where(
                CIRCULATION_RESERVATION.EDITION_ID.eq(editionId.value)
                    .and(CIRCULATION_RESERVATION.MEMBER_ID.ne(memberId.value))
                    .and(CIRCULATION_RESERVATION.STATUS.eq(ReservationStatus.WAITING.name)),
            ),
    )

    override fun findExpiredIds(now: Instant, batchSize: Int): List<ReservationId> =
        dsl.select(CIRCULATION_RESERVATION.ID)
            .from(CIRCULATION_RESERVATION)
            .where(
                CIRCULATION_RESERVATION.STATUS.eq(ReservationStatus.READY.name)
                    .and(CIRCULATION_RESERVATION.EXPIRES_AT.le(now.toOffsetDateTime())),
            )
            .orderBy(CIRCULATION_RESERVATION.EXPIRES_AT, CIRCULATION_RESERVATION.ID)
            .limit(batchSize)
            .fetch(CIRCULATION_RESERVATION.ID)
            .map(::ReservationId)

    private fun CirculationReservationRecord.toDomain(): Reservation = Reservation.restore(
        id = ReservationId(requireNotNull(id)),
        memberId = MemberId(requireNotNull(memberId)),
        editionId = EditionId(requireNotNull(editionId)),
        copyId = copyId?.let(::CopyId),
        status = ReservationStatus.valueOf(requireNotNull(status)),
        placedAt = requireNotNull(placedAt).toInstant(),
        readyAt = readyAt?.toInstant(),
        expiresAt = expiresAt?.toInstant(),
        fulfilledAt = fulfilledAt?.toInstant(),
        cancelledAt = cancelledAt?.toInstant(),
        version = requireNotNull(version),
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
