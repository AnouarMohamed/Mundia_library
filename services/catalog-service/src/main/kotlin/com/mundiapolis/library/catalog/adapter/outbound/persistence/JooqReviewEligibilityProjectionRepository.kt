package com.mundiapolis.library.catalog.adapter.outbound.persistence

import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONSUMER_INBOX
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_LOAN_REVIEW_PROJECTION
import com.mundiapolis.library.catalog.dto.CirculationLoanEvent
import com.mundiapolis.library.catalog.dto.ConsumerEventDisposition
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqReviewEligibilityProjectionRepository(
    private val dsl: DSLContext,
) {
    fun lockLoan(loanId: UUID) {
        dsl.fetch(
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), ?))",
            loanId.toString(),
            LOAN_LOCK_NAMESPACE,
        )
    }

    fun findInbox(consumerName: String, eventId: UUID): InboxEntry? = dsl
        .select(CATALOG_CONSUMER_INBOX.PAYLOAD_SHA256, CATALOG_CONSUMER_INBOX.DISPOSITION)
        .from(CATALOG_CONSUMER_INBOX)
        .where(
            CATALOG_CONSUMER_INBOX.CONSUMER_NAME.eq(consumerName)
                .and(CATALOG_CONSUMER_INBOX.EVENT_ID.eq(eventId)),
        )
        .fetchOne { record ->
            InboxEntry(
                requireNotNull(record[CATALOG_CONSUMER_INBOX.PAYLOAD_SHA256]).trim(),
                ConsumerEventDisposition.valueOf(
                    requireNotNull(record[CATALOG_CONSUMER_INBOX.DISPOSITION]),
                ),
            )
        }

    fun findLoan(loanId: UUID): ProjectedLoan? = dsl
        .selectFrom(CATALOG_LOAN_REVIEW_PROJECTION)
        .where(CATALOG_LOAN_REVIEW_PROJECTION.LOAN_ID.eq(loanId))
        .fetchOne { record ->
            ProjectedLoan(
                memberId = requireNotNull(record.memberId),
                editionId = requireNotNull(record.editionId),
                sourceVersion = requireNotNull(record.sourceVersion),
            )
        }

    fun saveLoan(event: CirculationLoanEvent, expectedVersion: Long?, now: Instant): Boolean {
        val nowValue = now.toOffsetDateTime()
        val occurredAt = event.occurredAt.toOffsetDateTime()
        val returnedAt = event.returnedAt?.toOffsetDateTime()
        return if (expectedVersion == null) {
            dsl.insertInto(CATALOG_LOAN_REVIEW_PROJECTION)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.LOAN_ID, event.loanId)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.MEMBER_ID, event.memberId)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.EDITION_ID, event.editionId)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.COPY_ID, event.copyId)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.STATUS, event.status.name)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.RETURNED_AT, returnedAt)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.SOURCE_VERSION, event.aggregateVersion)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.SOURCE_EVENT_ID, event.eventId)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.SOURCE_OCCURRED_AT, occurredAt)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.CREATED_AT, nowValue)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.UPDATED_AT, nowValue)
                .onConflictDoNothing()
                .execute() == 1
        } else {
            dsl.update(CATALOG_LOAN_REVIEW_PROJECTION)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.COPY_ID, event.copyId)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.STATUS, event.status.name)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.RETURNED_AT, returnedAt)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.SOURCE_VERSION, event.aggregateVersion)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.SOURCE_EVENT_ID, event.eventId)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.SOURCE_OCCURRED_AT, occurredAt)
                .set(CATALOG_LOAN_REVIEW_PROJECTION.UPDATED_AT, nowValue)
                .where(
                    CATALOG_LOAN_REVIEW_PROJECTION.LOAN_ID.eq(event.loanId)
                        .and(CATALOG_LOAN_REVIEW_PROJECTION.MEMBER_ID.eq(event.memberId))
                        .and(CATALOG_LOAN_REVIEW_PROJECTION.EDITION_ID.eq(event.editionId))
                        .and(CATALOG_LOAN_REVIEW_PROJECTION.SOURCE_VERSION.eq(expectedVersion)),
                )
                .execute() == 1
        }
    }

    fun appendInbox(
        consumerName: String,
        event: CirculationLoanEvent,
        disposition: ConsumerEventDisposition,
        now: Instant,
    ): Boolean = dsl.insertInto(CATALOG_CONSUMER_INBOX)
        .set(CATALOG_CONSUMER_INBOX.CONSUMER_NAME, consumerName)
        .set(CATALOG_CONSUMER_INBOX.EVENT_ID, event.eventId)
        .set(CATALOG_CONSUMER_INBOX.EVENT_TYPE, event.eventType)
        .set(CATALOG_CONSUMER_INBOX.EVENT_VERSION, event.eventVersion)
        .set(CATALOG_CONSUMER_INBOX.AGGREGATE_TYPE, LOAN_AGGREGATE)
        .set(CATALOG_CONSUMER_INBOX.AGGREGATE_ID, event.loanId)
        .set(CATALOG_CONSUMER_INBOX.AGGREGATE_VERSION, event.aggregateVersion)
        .set(CATALOG_CONSUMER_INBOX.PAYLOAD_SHA256, event.payloadSha256)
        .set(CATALOG_CONSUMER_INBOX.DISPOSITION, disposition.name)
        .set(CATALOG_CONSUMER_INBOX.RECEIVED_AT, now.toOffsetDateTime())
        .set(CATALOG_CONSUMER_INBOX.PROCESSED_AT, now.toOffsetDateTime())
        .onConflictDoNothing()
        .execute() == 1

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    data class InboxEntry(val payloadSha256: String, val disposition: ConsumerEventDisposition)

    data class ProjectedLoan(val memberId: UUID, val editionId: UUID, val sourceVersion: Long)

    private companion object {
        const val LOAN_AGGREGATE = "loan"
        const val LOAN_LOCK_NAMESPACE = 0x4341544CL
    }
}
