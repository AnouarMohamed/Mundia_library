package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_COPY
import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqCopyStore(
    private val dsl: DSLContext,
) : CopyStore {
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

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val AVAILABLE = "AVAILABLE"
        const val ON_LOAN = "ON_LOAN"
    }
}
