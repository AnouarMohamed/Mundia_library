package com.mundiapolis.library.catalog.adapter.outbound.persistence

import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_AUDIT_ENTRY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_COMMAND_IDEMPOTENCY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONTRIBUTOR
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_OUTBOX_EVENT
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK_CONTRIBUTOR
import com.mundiapolis.library.catalog.dto.CatalogCommandConflictException
import com.mundiapolis.library.catalog.dto.CatalogCommandExecution
import com.mundiapolis.library.catalog.dto.CatalogCommandResult
import com.mundiapolis.library.catalog.dto.CatalogIdempotencyConflictException
import com.mundiapolis.library.catalog.dto.CatalogIdempotencyIncompleteException
import com.mundiapolis.library.catalog.dto.CreateEditionCommand
import com.mundiapolis.library.catalog.dto.CreateWorkCommand
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqCatalogCommandRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    fun createWork(
        command: CreateWorkCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx = tx,
            ownerFingerprint = command.ownerFingerprint,
            idempotencyKey = command.idempotencyKey,
            operation = CREATE_WORK,
            requestFingerprint = requestFingerprint,
            now = now,
        ) {
            val timestamp = now.toOffsetDateTime()
            val inserted = tx.insertInto(CATALOG_WORK)
                .set(CATALOG_WORK.WORK_ID, command.workId)
                .set(CATALOG_WORK.TITLE, command.title)
                .set(CATALOG_WORK.SUMMARY, command.summary)
                .set(CATALOG_WORK.DESCRIPTION, command.description)
                .set(CATALOG_WORK.GENRE, command.genre)
                .set(CATALOG_WORK.RATING, java.math.BigDecimal.ZERO)
                .set(CATALOG_WORK.RATING_COUNT, 0)
                .set(CATALOG_WORK.CREATED_AT, timestamp)
                .set(CATALOG_WORK.UPDATED_AT, timestamp)
                .set(CATALOG_WORK.AGGREGATE_VERSION, INITIAL_VERSION)
                .onConflictDoNothing()
                .execute()
            if (inserted != 1) {
                throw CatalogCommandConflictException("Work already exists")
            }

            command.authors.forEachIndexed { index, author ->
                tx.insertInto(CATALOG_CONTRIBUTOR)
                    .set(CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID, author.contributorId)
                    .set(CATALOG_CONTRIBUTOR.NAME, author.name)
                    .set(CATALOG_CONTRIBUTOR.BIOGRAPHY, author.bio)
                    .set(CATALOG_CONTRIBUTOR.CREATED_AT, timestamp)
                    .set(CATALOG_CONTRIBUTOR.UPDATED_AT, timestamp)
                    .onConflictDoNothing()
                    .execute()
                val existing = tx.selectFrom(CATALOG_CONTRIBUTOR)
                    .where(CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID.eq(author.contributorId))
                    .fetchOne()
                    ?: error("Contributor insert was not observable")
                if (existing.name != author.name || existing.biography != author.bio) {
                    throw CatalogCommandConflictException(
                        "Contributor ${author.contributorId} already has different metadata",
                    )
                }
                tx.insertInto(CATALOG_WORK_CONTRIBUTOR)
                    .set(CATALOG_WORK_CONTRIBUTOR.WORK_ID, command.workId)
                    .set(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTOR_ID, author.contributorId)
                    .set(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTION_ROLE, AUTHOR_ROLE)
                    .set(CATALOG_WORK_CONTRIBUTOR.DISPLAY_ORDER, index)
                    .execute()
            }

            val state = linkedMapOf<String, Any?>(
                "workId" to command.workId.toString(),
                "title" to command.title,
                "summary" to command.summary,
                "description" to command.description,
                "genre" to command.genre,
                "rating" to 0,
                "authors" to command.authors.map { author ->
                    linkedMapOf(
                        "contributorId" to author.contributorId.toString(),
                        "name" to author.name,
                        "bio" to author.bio,
                    )
                },
            )
            persistAuditAndOutbox(
                tx = tx,
                operation = CREATE_WORK,
                aggregateType = WORK_AGGREGATE,
                aggregateId = command.workId,
                eventType = WORK_CREATED_EVENT,
                state = state,
                ownerFingerprint = command.ownerFingerprint,
                reason = command.reason,
                now = now,
            )
            CatalogCommandResult(WORK_AGGREGATE, command.workId, INITIAL_VERSION, now)
        }
    }

    fun createEdition(
        command: CreateEditionCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx = tx,
            ownerFingerprint = command.ownerFingerprint,
            idempotencyKey = command.idempotencyKey,
            operation = CREATE_EDITION,
            requestFingerprint = requestFingerprint,
            now = now,
        ) {
            if (!tx.fetchExists(CATALOG_WORK, CATALOG_WORK.WORK_ID.eq(command.workId))) {
                throw CatalogCommandConflictException("Parent work does not exist")
            }
            val timestamp = now.toOffsetDateTime()
            val inserted = tx.insertInto(CATALOG_EDITION)
                .set(CATALOG_EDITION.EDITION_ID, command.editionId)
                .set(CATALOG_EDITION.WORK_ID, command.workId)
                .set(CATALOG_EDITION.TITLE, command.title)
                .set(CATALOG_EDITION.ISBN, command.isbn)
                .set(CATALOG_EDITION.PUBLISHER, command.publisher)
                .set(CATALOG_EDITION.PUBLICATION_YEAR, command.publicationYear)
                .set(CATALOG_EDITION.LANGUAGE, command.language)
                .set(CATALOG_EDITION.PAGE_COUNT, command.pageCount)
                .set(CATALOG_EDITION.COVER_URL, command.coverUrl)
                .set(CATALOG_EDITION.COVER_COLOR, command.coverColor)
                .set(CATALOG_EDITION.VIDEO_URL, command.videoUrl)
                .set(CATALOG_EDITION.IS_ACTIVE, command.active)
                .set(CATALOG_EDITION.CREATED_AT, timestamp)
                .set(CATALOG_EDITION.UPDATED_AT, timestamp)
                .set(CATALOG_EDITION.AGGREGATE_VERSION, INITIAL_VERSION)
                .onConflictDoNothing()
                .execute()
            if (inserted != 1) {
                throw CatalogCommandConflictException("Edition or ISBN already exists")
            }

            val state = linkedMapOf<String, Any?>(
                "editionId" to command.editionId.toString(),
                "workId" to command.workId.toString(),
                "title" to command.title,
                "isbn" to command.isbn,
                "publisher" to command.publisher,
                "publicationYear" to command.publicationYear,
                "language" to command.language,
                "pageCount" to command.pageCount,
                "coverUrl" to command.coverUrl,
                "coverColor" to command.coverColor,
                "videoUrl" to command.videoUrl,
                "isActive" to command.active,
            )
            persistAuditAndOutbox(
                tx = tx,
                operation = CREATE_EDITION,
                aggregateType = EDITION_AGGREGATE,
                aggregateId = command.editionId,
                eventType = EDITION_CREATED_EVENT,
                state = state,
                ownerFingerprint = command.ownerFingerprint,
                reason = command.reason,
                now = now,
            )
            CatalogCommandResult(EDITION_AGGREGATE, command.editionId, INITIAL_VERSION, now)
        }
    }

    private fun executeIdempotently(
        tx: DSLContext,
        ownerFingerprint: String,
        idempotencyKey: String,
        operation: String,
        requestFingerprint: String,
        now: Instant,
        action: () -> CatalogCommandResult,
    ): CatalogCommandExecution {
        val claimed = tx.insertInto(CATALOG_COMMAND_IDEMPOTENCY)
            .set(CATALOG_COMMAND_IDEMPOTENCY.OWNER_FINGERPRINT, ownerFingerprint)
            .set(CATALOG_COMMAND_IDEMPOTENCY.IDEMPOTENCY_KEY, idempotencyKey)
            .set(CATALOG_COMMAND_IDEMPOTENCY.OPERATION, operation)
            .set(CATALOG_COMMAND_IDEMPOTENCY.REQUEST_FINGERPRINT, requestFingerprint)
            .set(CATALOG_COMMAND_IDEMPOTENCY.CREATED_AT, now.toOffsetDateTime())
            .set(CATALOG_COMMAND_IDEMPOTENCY.EXPIRES_AT, now.plusSeconds(RETENTION_SECONDS).toOffsetDateTime())
            .onConflictDoNothing()
            .execute() == 1
        if (!claimed) {
            val stored = tx.selectFrom(CATALOG_COMMAND_IDEMPOTENCY)
                .where(
                    CATALOG_COMMAND_IDEMPOTENCY.OWNER_FINGERPRINT.eq(ownerFingerprint)
                        .and(CATALOG_COMMAND_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(idempotencyKey)),
                )
                .fetchOne()
                ?: throw CatalogIdempotencyIncompleteException()
            if (stored.operation != operation || stored.requestFingerprint?.trim() != requestFingerprint) {
                throw CatalogIdempotencyConflictException()
            }
            if (stored.completedAt == null) {
                throw CatalogIdempotencyIncompleteException()
            }
            return CatalogCommandExecution(
                result = CatalogCommandResult(
                    aggregateType = requireNotNull(stored.aggregateType),
                    aggregateId = requireNotNull(stored.aggregateId),
                    aggregateVersion = requireNotNull(stored.aggregateVersion),
                    occurredAt = requireNotNull(stored.occurredAt).toInstant(),
                ),
                replayed = true,
            )
        }

        val result = action()
        val completed = tx.update(CATALOG_COMMAND_IDEMPOTENCY)
            .set(CATALOG_COMMAND_IDEMPOTENCY.RESPONSE_STATUS, CREATED_STATUS)
            .set(CATALOG_COMMAND_IDEMPOTENCY.AGGREGATE_TYPE, result.aggregateType)
            .set(CATALOG_COMMAND_IDEMPOTENCY.AGGREGATE_ID, result.aggregateId)
            .set(CATALOG_COMMAND_IDEMPOTENCY.AGGREGATE_VERSION, result.aggregateVersion)
            .set(CATALOG_COMMAND_IDEMPOTENCY.OCCURRED_AT, result.occurredAt.toOffsetDateTime())
            .set(CATALOG_COMMAND_IDEMPOTENCY.COMPLETED_AT, now.toOffsetDateTime())
            .where(
                CATALOG_COMMAND_IDEMPOTENCY.OWNER_FINGERPRINT.eq(ownerFingerprint)
                    .and(CATALOG_COMMAND_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(idempotencyKey))
                    .and(CATALOG_COMMAND_IDEMPOTENCY.COMPLETED_AT.isNull),
            )
            .execute()
        check(completed == 1) { "Catalog idempotency result was not persisted" }
        return CatalogCommandExecution(result, replayed = false)
    }

    private fun persistAuditAndOutbox(
        tx: DSLContext,
        operation: String,
        aggregateType: String,
        aggregateId: UUID,
        eventType: String,
        state: Map<String, Any?>,
        ownerFingerprint: String,
        reason: String,
        now: Instant,
    ) {
        val stateJson = JSON.valueOf(objectMapper.writeValueAsString(state))
        tx.insertInto(CATALOG_AUDIT_ENTRY)
            .set(CATALOG_AUDIT_ENTRY.AUDIT_ID, UUID.randomUUID())
            .set(CATALOG_AUDIT_ENTRY.AGGREGATE_TYPE, aggregateType)
            .set(CATALOG_AUDIT_ENTRY.AGGREGATE_ID, aggregateId)
            .set(CATALOG_AUDIT_ENTRY.AGGREGATE_VERSION, INITIAL_VERSION)
            .set(CATALOG_AUDIT_ENTRY.OPERATION, operation)
            .set(CATALOG_AUDIT_ENTRY.ACTOR_FINGERPRINT, ownerFingerprint)
            .set(CATALOG_AUDIT_ENTRY.REASON, reason)
            .set(CATALOG_AUDIT_ENTRY.RESULTING_STATE, stateJson)
            .set(CATALOG_AUDIT_ENTRY.OCCURRED_AT, now.toOffsetDateTime())
            .execute()
        val headers = mapOf(
            "contentType" to "application/json",
            "schema" to "$eventType.v1",
        )
        tx.insertInto(CATALOG_OUTBOX_EVENT)
            .set(CATALOG_OUTBOX_EVENT.EVENT_ID, UUID.randomUUID())
            .set(CATALOG_OUTBOX_EVENT.AGGREGATE_TYPE, aggregateType)
            .set(CATALOG_OUTBOX_EVENT.AGGREGATE_ID, aggregateId)
            .set(CATALOG_OUTBOX_EVENT.AGGREGATE_VERSION, INITIAL_VERSION)
            .set(CATALOG_OUTBOX_EVENT.EVENT_TYPE, eventType)
            .set(CATALOG_OUTBOX_EVENT.EVENT_VERSION, EVENT_VERSION)
            .set(CATALOG_OUTBOX_EVENT.OCCURRED_AT, now.toOffsetDateTime())
            .set(CATALOG_OUTBOX_EVENT.PAYLOAD, stateJson)
            .set(CATALOG_OUTBOX_EVENT.HEADERS, JSON.valueOf(objectMapper.writeValueAsString(headers)))
            .set(CATALOG_OUTBOX_EVENT.CREATED_AT, now.toOffsetDateTime())
            .execute()
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val CREATE_WORK = "CREATE_WORK"
        const val CREATE_EDITION = "CREATE_EDITION"
        const val WORK_AGGREGATE = "work"
        const val EDITION_AGGREGATE = "edition"
        const val WORK_CREATED_EVENT = "catalog.work.created"
        const val EDITION_CREATED_EVENT = "catalog.edition.created"
        const val AUTHOR_ROLE = "AUTHOR"
        const val INITIAL_VERSION = 0L
        const val EVENT_VERSION = 1
        const val CREATED_STATUS = 201
        const val RETENTION_SECONDS = 86_400L * 7
    }
}
