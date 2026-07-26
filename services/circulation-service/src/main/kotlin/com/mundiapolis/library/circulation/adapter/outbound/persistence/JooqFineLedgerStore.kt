package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE_LEDGER_ENTRY
import com.mundiapolis.library.circulation.application.port.outbound.FineLedgerStore
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntry
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqFineLedgerStore(
    private val dsl: DSLContext,
) : FineLedgerStore {
    override fun append(entry: FineLedgerEntry): Boolean =
        dsl.insertInto(CIRCULATION_FINE_LEDGER_ENTRY)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.ID, entry.id.value)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID, entry.fineId.value)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_VERSION, entry.fineVersion)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.ENTRY_TYPE, entry.type.name)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.DELTA_MINOR, entry.deltaMinor)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.ACTOR_FINGERPRINT, entry.actorFingerprint)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.REASON, entry.reason)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.EXTERNAL_REFERENCE, entry.externalReference)
            .set(CIRCULATION_FINE_LEDGER_ENTRY.OCCURRED_AT, entry.occurredAt.toOffsetDateTime())
            .set(CIRCULATION_FINE_LEDGER_ENTRY.CREATED_AT, entry.occurredAt.toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
