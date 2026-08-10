package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_COPY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationCopyRecord
import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.domain.model.BranchId
import com.mundiapolis.library.circulation.domain.model.Copy
import com.mundiapolis.library.circulation.domain.model.CopyBarcode
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.CopyStatus
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.ShelfLocation
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqCopyStore(
    private val dsl: DSLContext,
) : CopyStore {
    override fun create(copy: Copy, now: Instant): Boolean =
        dsl.insertInto(CIRCULATION_COPY)
            .set(CIRCULATION_COPY.ID, copy.id.value)
            .set(CIRCULATION_COPY.EDITION_ID, copy.editionId.value)
            .set(CIRCULATION_COPY.BRANCH_ID, copy.branchId.value)
            .set(CIRCULATION_COPY.BARCODE, copy.barcode.value)
            .set(CIRCULATION_COPY.STATUS, copy.status.name)
            .set(CIRCULATION_COPY.SHELF_LOCATION, copy.shelfLocation?.value)
            .set(CIRCULATION_COPY.VERSION, copy.version)
            .set(CIRCULATION_COPY.CREATED_AT, now.toOffsetDateTime())
            .set(CIRCULATION_COPY.UPDATED_AT, now.toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1

    override fun findById(id: CopyId): Copy? =
        dsl.selectFrom(CIRCULATION_COPY)
            .where(CIRCULATION_COPY.ID.eq(id.value))
            .fetchOne()
            ?.toDomain()

    override fun lockById(id: CopyId): Copy? =
        dsl.selectFrom(CIRCULATION_COPY)
            .where(CIRCULATION_COPY.ID.eq(id.value))
            .forUpdate()
            .fetchOne()
            ?.toDomain()

    override fun update(copy: Copy, expectedVersion: Long, now: Instant): Boolean =
        dsl.update(CIRCULATION_COPY)
            .set(CIRCULATION_COPY.BRANCH_ID, copy.branchId.value)
            .set(CIRCULATION_COPY.STATUS, copy.status.name)
            .set(CIRCULATION_COPY.SHELF_LOCATION, copy.shelfLocation?.value)
            .set(CIRCULATION_COPY.VERSION, copy.version)
            .set(CIRCULATION_COPY.UPDATED_AT, now.toOffsetDateTime())
            .where(
                CIRCULATION_COPY.ID.eq(copy.id.value)
                    .and(CIRCULATION_COPY.VERSION.eq(expectedVersion)),
            )
            .execute() == 1

    override fun allocateAvailable(editionId: EditionId, now: Instant): CopyId? {
        val candidate = dsl
            .select(CIRCULATION_COPY.ID, CIRCULATION_COPY.VERSION)
            .from(CIRCULATION_COPY)
            .where(
                CIRCULATION_COPY.EDITION_ID.eq(editionId.value)
                    .and(CIRCULATION_COPY.STATUS.eq(AVAILABLE)),
            )
            .orderBy(CIRCULATION_COPY.BARCODE.asc(), CIRCULATION_COPY.ID.asc())
            .limit(1)
            .forUpdate()
            .skipLocked()
            .fetchOne()
            ?: return null

        val copyId = requireNotNull(candidate.get(CIRCULATION_COPY.ID))
        val version = requireNotNull(candidate.get(CIRCULATION_COPY.VERSION))
        val updated = dsl.update(CIRCULATION_COPY)
            .set(CIRCULATION_COPY.STATUS, ON_LOAN)
            .set(CIRCULATION_COPY.VERSION, version + 1)
            .set(CIRCULATION_COPY.UPDATED_AT, now.toOffsetDateTime())
            .where(
                CIRCULATION_COPY.ID.eq(copyId)
                    .and(CIRCULATION_COPY.STATUS.eq(AVAILABLE))
                    .and(CIRCULATION_COPY.VERSION.eq(version)),
            )
            .execute()
        if (updated != 1) {
            throw ConcurrentCirculationUpdateException()
        }

        return CopyId(copyId)
    }

    override fun release(copyId: CopyId, now: Instant): Boolean =
        dsl.update(CIRCULATION_COPY)
            .set(CIRCULATION_COPY.STATUS, AVAILABLE)
            .set(CIRCULATION_COPY.VERSION, CIRCULATION_COPY.VERSION.plus(1L))
            .set(CIRCULATION_COPY.UPDATED_AT, now.toOffsetDateTime())
            .where(
                CIRCULATION_COPY.ID.eq(copyId.value)
                    .and(CIRCULATION_COPY.STATUS.eq(ON_LOAN)),
            )
            .execute() == 1

    override fun reserveAvailable(editionId: EditionId, now: Instant): CopyId? =
        transitionAvailable(editionId, RESERVED, now)

    override fun reservedToLoan(copyId: CopyId, now: Instant): Boolean =
        transition(copyId, RESERVED, ON_LOAN, now)

    override fun releaseReserved(copyId: CopyId, now: Instant): Boolean =
        transition(copyId, RESERVED, AVAILABLE, now)

    override fun returnToReservation(copyId: CopyId, now: Instant): Boolean =
        transition(copyId, ON_LOAN, RESERVED, now)

    override fun reserve(copyId: CopyId, now: Instant): Boolean =
        transition(copyId, AVAILABLE, RESERVED, now)

    private fun transitionAvailable(
        editionId: EditionId,
        target: String,
        now: Instant,
    ): CopyId? {
        val candidate = dsl
            .select(CIRCULATION_COPY.ID, CIRCULATION_COPY.VERSION)
            .from(CIRCULATION_COPY)
            .where(
                CIRCULATION_COPY.EDITION_ID.eq(editionId.value)
                    .and(CIRCULATION_COPY.STATUS.eq(AVAILABLE)),
            )
            .orderBy(CIRCULATION_COPY.BARCODE.asc(), CIRCULATION_COPY.ID.asc())
            .limit(1)
            .forUpdate()
            .skipLocked()
            .fetchOne()
            ?: return null
        val copyId = requireNotNull(candidate.get(CIRCULATION_COPY.ID))
        val version = requireNotNull(candidate.get(CIRCULATION_COPY.VERSION))
        val updated = dsl.update(CIRCULATION_COPY)
            .set(CIRCULATION_COPY.STATUS, target)
            .set(CIRCULATION_COPY.VERSION, version + 1)
            .set(CIRCULATION_COPY.UPDATED_AT, now.toOffsetDateTime())
            .where(
                CIRCULATION_COPY.ID.eq(copyId)
                    .and(CIRCULATION_COPY.STATUS.eq(AVAILABLE))
                    .and(CIRCULATION_COPY.VERSION.eq(version)),
            )
            .execute()
        if (updated != 1) {
            throw ConcurrentCirculationUpdateException()
        }
        return CopyId(copyId)
    }

    private fun transition(
        copyId: CopyId,
        expected: String,
        target: String,
        now: Instant,
    ): Boolean = dsl.update(CIRCULATION_COPY)
        .set(CIRCULATION_COPY.STATUS, target)
        .set(CIRCULATION_COPY.VERSION, CIRCULATION_COPY.VERSION.plus(1L))
        .set(CIRCULATION_COPY.UPDATED_AT, now.toOffsetDateTime())
        .where(
            CIRCULATION_COPY.ID.eq(copyId.value)
                .and(CIRCULATION_COPY.STATUS.eq(expected)),
        )
        .execute() == 1

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private fun CirculationCopyRecord.toDomain(): Copy = Copy.restore(
        id = CopyId(requireNotNull(id)),
        editionId = EditionId(requireNotNull(editionId)),
        branchId = BranchId(requireNotNull(branchId)),
        barcode = CopyBarcode.parse(requireNotNull(barcode)),
        status = CopyStatus.valueOf(requireNotNull(status)),
        shelfLocation = shelfLocation?.let(ShelfLocation::parse),
        version = requireNotNull(version),
    )

    private companion object {
        const val AVAILABLE = "AVAILABLE"
        const val ON_LOAN = "ON_LOAN"
        const val RESERVED = "RESERVED"
    }
}
