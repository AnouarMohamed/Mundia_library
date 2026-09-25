package com.mundiapolis.library.catalog.adapter.outbound.persistence

import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_AUDIT_ENTRY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_COMMAND_IDEMPOTENCY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONTRIBUTOR
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_LOAN_REVIEW_PROJECTION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_OUTBOX_EVENT
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_REVIEW
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK_CONTRIBUTOR
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.tables.records.CatalogEditionRecord
import com.mundiapolis.library.catalog.dto.CatalogCommandConflictException
import com.mundiapolis.library.catalog.dto.CatalogCommandExecution
import com.mundiapolis.library.catalog.dto.CatalogCommandNotFoundException
import com.mundiapolis.library.catalog.dto.CatalogCommandResult
import com.mundiapolis.library.catalog.dto.CatalogIdempotencyConflictException
import com.mundiapolis.library.catalog.dto.CatalogIdempotencyIncompleteException
import com.mundiapolis.library.catalog.dto.CreateEditionCommand
import com.mundiapolis.library.catalog.dto.CreateReviewCommand
import com.mundiapolis.library.catalog.dto.CreateWorkCommand
import com.mundiapolis.library.catalog.dto.DeleteReviewCommand
import com.mundiapolis.library.catalog.dto.ReviewNotEligibleException
import com.mundiapolis.library.catalog.dto.SetEditionActiveCommand
import com.mundiapolis.library.catalog.dto.UpdateEditionCommand
import com.mundiapolis.library.catalog.dto.UpdateReviewCommand
import com.mundiapolis.library.catalog.dto.UpdateWorkCommand
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.math.BigDecimal
import java.math.RoundingMode
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
            responseStatus = CREATED_STATUS,
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
                aggregateVersion = INITIAL_VERSION,
                previousState = null,
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
            responseStatus = CREATED_STATUS,
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
                aggregateVersion = INITIAL_VERSION,
                previousState = null,
            )
            CatalogCommandResult(EDITION_AGGREGATE, command.editionId, INITIAL_VERSION, now)
        }
    }

    fun updateWork(
        command: UpdateWorkCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx = tx,
            ownerFingerprint = command.ownerFingerprint,
            idempotencyKey = command.idempotencyKey,
            operation = UPDATE_WORK,
            responseStatus = OK_STATUS,
            requestFingerprint = requestFingerprint,
            now = now,
        ) {
            val existing = tx.selectFrom(CATALOG_WORK)
                .where(CATALOG_WORK.WORK_ID.eq(command.workId))
                .forUpdate()
                .fetchOne()
                ?: throw CatalogCommandNotFoundException("Work does not exist")
            val currentVersion = requireNotNull(existing.aggregateVersion)
            if (currentVersion != command.expectedVersion) {
                throw CatalogCommandConflictException(
                    "Work version is $currentVersion, not ${command.expectedVersion}",
                )
            }
            val previousState = workState(tx, command.workId)
            replaceAuthors(tx, command, now)
            val nextVersion = Math.incrementExact(currentVersion)
            val updated = tx.update(CATALOG_WORK)
                .set(CATALOG_WORK.TITLE, command.title)
                .set(CATALOG_WORK.SUMMARY, command.summary)
                .set(CATALOG_WORK.DESCRIPTION, command.description)
                .set(CATALOG_WORK.GENRE, command.genre)
                .set(CATALOG_WORK.AGGREGATE_VERSION, nextVersion)
                .set(CATALOG_WORK.UPDATED_AT, now.toOffsetDateTime())
                .where(
                    CATALOG_WORK.WORK_ID.eq(command.workId)
                        .and(CATALOG_WORK.AGGREGATE_VERSION.eq(currentVersion)),
                )
                .execute()
            check(updated == 1) { "Locked work update was lost" }
            val resultingState = linkedMapOf<String, Any?>(
                "workId" to command.workId.toString(),
                "title" to command.title,
                "summary" to command.summary,
                "description" to command.description,
                "genre" to command.genre,
                "rating" to requireNotNull(existing.rating).toDouble(),
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
                operation = UPDATE_WORK,
                aggregateType = WORK_AGGREGATE,
                aggregateId = command.workId,
                eventType = WORK_UPDATED_EVENT,
                state = resultingState,
                ownerFingerprint = command.ownerFingerprint,
                reason = command.reason,
                now = now,
                aggregateVersion = nextVersion,
                previousState = previousState,
            )
            CatalogCommandResult(WORK_AGGREGATE, command.workId, nextVersion, now)
        }
    }

    fun updateEdition(
        command: UpdateEditionCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx = tx,
            ownerFingerprint = command.ownerFingerprint,
            idempotencyKey = command.idempotencyKey,
            operation = UPDATE_EDITION,
            responseStatus = OK_STATUS,
            requestFingerprint = requestFingerprint,
            now = now,
        ) {
            val existing = lockEdition(tx, command.editionId)
            val currentVersion = requireNotNull(existing.aggregateVersion)
            if (currentVersion != command.expectedVersion) {
                throw CatalogCommandConflictException(
                    "Edition version is $currentVersion, not ${command.expectedVersion}",
                )
            }
            val previousState = editionState(existing)
            val nextVersion = Math.incrementExact(currentVersion)
            val updated = try {
                tx.update(CATALOG_EDITION)
                    .set(CATALOG_EDITION.TITLE, command.title)
                    .set(CATALOG_EDITION.ISBN, command.isbn)
                    .set(CATALOG_EDITION.PUBLISHER, command.publisher)
                    .set(CATALOG_EDITION.PUBLICATION_YEAR, command.publicationYear)
                    .set(CATALOG_EDITION.LANGUAGE, command.language)
                    .set(CATALOG_EDITION.PAGE_COUNT, command.pageCount)
                    .set(CATALOG_EDITION.COVER_URL, command.coverUrl)
                    .set(CATALOG_EDITION.COVER_COLOR, command.coverColor)
                    .set(CATALOG_EDITION.VIDEO_URL, command.videoUrl)
                    .set(CATALOG_EDITION.AGGREGATE_VERSION, nextVersion)
                    .set(CATALOG_EDITION.UPDATED_AT, now.toOffsetDateTime())
                    .where(
                        CATALOG_EDITION.EDITION_ID.eq(command.editionId)
                            .and(CATALOG_EDITION.AGGREGATE_VERSION.eq(currentVersion)),
                    )
                    .execute()
            } catch (exception: DataAccessException) {
                if (exception.sqlState() == UNIQUE_VIOLATION_SQLSTATE) {
                    throw CatalogCommandConflictException("Edition ISBN already exists")
                }
                throw exception
            }
            check(updated == 1) { "Locked edition update was lost" }
            val resultingState = editionState(
                editionId = command.editionId,
                workId = requireNotNull(existing.workId),
                title = command.title,
                isbn = command.isbn,
                publisher = command.publisher,
                publicationYear = command.publicationYear,
                language = command.language,
                pageCount = command.pageCount,
                coverUrl = command.coverUrl,
                coverColor = command.coverColor,
                videoUrl = command.videoUrl,
                active = requireNotNull(existing.isActive),
            )
            persistAuditAndOutbox(
                tx = tx,
                operation = UPDATE_EDITION,
                aggregateType = EDITION_AGGREGATE,
                aggregateId = command.editionId,
                eventType = EDITION_UPDATED_EVENT,
                state = resultingState,
                ownerFingerprint = command.ownerFingerprint,
                reason = command.reason,
                now = now,
                aggregateVersion = nextVersion,
                previousState = previousState,
            )
            CatalogCommandResult(EDITION_AGGREGATE, command.editionId, nextVersion, now)
        }
    }

    fun setEditionActive(
        command: SetEditionActiveCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx = tx,
            ownerFingerprint = command.ownerFingerprint,
            idempotencyKey = command.idempotencyKey,
            operation = SET_EDITION_ACTIVE,
            responseStatus = OK_STATUS,
            requestFingerprint = requestFingerprint,
            now = now,
        ) {
            val existing = lockEdition(tx, command.editionId)
            val currentVersion = requireNotNull(existing.aggregateVersion)
            if (currentVersion != command.expectedVersion) {
                throw CatalogCommandConflictException(
                    "Edition version is $currentVersion, not ${command.expectedVersion}",
                )
            }
            if (existing.isActive == command.active) {
                throw CatalogCommandConflictException("Edition already has the requested active state")
            }
            val nextVersion = Math.incrementExact(currentVersion)
            val updated = tx.update(CATALOG_EDITION)
                .set(CATALOG_EDITION.IS_ACTIVE, command.active)
                .set(CATALOG_EDITION.AGGREGATE_VERSION, nextVersion)
                .set(CATALOG_EDITION.UPDATED_AT, now.toOffsetDateTime())
                .where(
                    CATALOG_EDITION.EDITION_ID.eq(command.editionId)
                        .and(CATALOG_EDITION.AGGREGATE_VERSION.eq(currentVersion)),
                )
                .execute()
            check(updated == 1) { "Locked edition activation update was lost" }
            val previousState = editionState(existing)
            val resultingState = previousState.toMutableMap().apply {
                this["isActive"] = command.active
            }
            persistAuditAndOutbox(
                tx = tx,
                operation = SET_EDITION_ACTIVE,
                aggregateType = EDITION_AGGREGATE,
                aggregateId = command.editionId,
                eventType = EDITION_ACTIVATION_CHANGED_EVENT,
                state = resultingState,
                ownerFingerprint = command.ownerFingerprint,
                reason = command.reason,
                now = now,
                aggregateVersion = nextVersion,
                previousState = previousState,
            )
            CatalogCommandResult(EDITION_AGGREGATE, command.editionId, nextVersion, now)
        }
    }

    fun createReview(
        command: CreateReviewCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx,
            command.ownerFingerprint,
            command.idempotencyKey,
            CREATE_REVIEW,
            CREATED_STATUS,
            requestFingerprint,
            now,
        ) {
            lockWork(tx, command.workId)
            val eligible = tx.fetchExists(
                tx.selectOne()
                    .from(CATALOG_LOAN_REVIEW_PROJECTION)
                    .join(CATALOG_EDITION)
                    .on(CATALOG_EDITION.EDITION_ID.eq(CATALOG_LOAN_REVIEW_PROJECTION.EDITION_ID))
                    .where(
                        CATALOG_LOAN_REVIEW_PROJECTION.MEMBER_ID.eq(command.memberId)
                            .and(CATALOG_LOAN_REVIEW_PROJECTION.STATUS.eq(RETURNED_STATUS))
                            .and(CATALOG_EDITION.WORK_ID.eq(command.workId)),
                    ),
            )
            if (!eligible) throw ReviewNotEligibleException()
            if (
                tx.fetchExists(
                    CATALOG_REVIEW,
                    CATALOG_REVIEW.WORK_ID.eq(command.workId)
                        .and(CATALOG_REVIEW.MEMBER_ID.eq(command.memberId)),
                )
            ) {
                throw CatalogCommandConflictException("Member already reviewed this work")
            }
            val reviewId = UUID.randomUUID()
            val state = reviewState(reviewId, command.workId, command.rating, PUBLISHED_STATUS, false)
            tx.insertInto(CATALOG_REVIEW)
                .set(CATALOG_REVIEW.REVIEW_ID, reviewId)
                .set(CATALOG_REVIEW.WORK_ID, command.workId)
                .set(CATALOG_REVIEW.MEMBER_ID, command.memberId)
                .set(CATALOG_REVIEW.RATING, command.rating.toShort())
                .set(CATALOG_REVIEW.CONTENT, command.content)
                .set(CATALOG_REVIEW.MODERATION_STATUS, PUBLISHED_STATUS)
                .set(CATALOG_REVIEW.CREATED_AT, now.toOffsetDateTime())
                .set(CATALOG_REVIEW.UPDATED_AT, now.toOffsetDateTime())
                .set(CATALOG_REVIEW.AGGREGATE_VERSION, INITIAL_VERSION)
                .execute()
            refreshWorkRating(tx, command.workId, now)
            persistAuditAndOutbox(
                tx,
                CREATE_REVIEW,
                REVIEW_AGGREGATE,
                reviewId,
                REVIEW_CREATED_EVENT,
                state,
                command.ownerFingerprint,
                CREATE_REVIEW_REASON,
                now,
                INITIAL_VERSION,
                null,
            )
            CatalogCommandResult(REVIEW_AGGREGATE, reviewId, INITIAL_VERSION, now)
        }
    }

    fun updateReview(
        command: UpdateReviewCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx,
            command.ownerFingerprint,
            command.idempotencyKey,
            UPDATE_REVIEW,
            OK_STATUS,
            requestFingerprint,
            now,
        ) {
            val workId = findOwnedReviewWork(tx, command.reviewId, command.memberId)
            lockWork(tx, workId)
            val existing = lockOwnedReview(tx, command.reviewId, command.memberId)
            val currentVersion = requireNotNull(existing.aggregateVersion)
            if (currentVersion != command.expectedVersion) {
                throw CatalogCommandConflictException(
                    "Review version is $currentVersion, not ${command.expectedVersion}",
                )
            }
            val nextVersion = Math.incrementExact(currentVersion)
            val previousState = reviewState(
                command.reviewId,
                workId,
                requireNotNull(existing.rating).toInt(),
                requireNotNull(existing.moderationStatus),
                false,
            )
            tx.update(CATALOG_REVIEW)
                .set(CATALOG_REVIEW.RATING, command.rating.toShort())
                .set(CATALOG_REVIEW.CONTENT, command.content)
                .set(CATALOG_REVIEW.AGGREGATE_VERSION, nextVersion)
                .set(CATALOG_REVIEW.UPDATED_AT, now.toOffsetDateTime())
                .where(
                    CATALOG_REVIEW.REVIEW_ID.eq(command.reviewId)
                        .and(CATALOG_REVIEW.AGGREGATE_VERSION.eq(currentVersion)),
                )
                .execute()
                .also { check(it == 1) { "Locked review update was lost" } }
            refreshWorkRating(tx, workId, now)
            val state = reviewState(
                command.reviewId,
                workId,
                command.rating,
                requireNotNull(existing.moderationStatus),
                false,
            )
            persistAuditAndOutbox(
                tx,
                UPDATE_REVIEW,
                REVIEW_AGGREGATE,
                command.reviewId,
                REVIEW_UPDATED_EVENT,
                state,
                command.ownerFingerprint,
                UPDATE_REVIEW_REASON,
                now,
                nextVersion,
                previousState,
            )
            CatalogCommandResult(REVIEW_AGGREGATE, command.reviewId, nextVersion, now)
        }
    }

    fun deleteReview(
        command: DeleteReviewCommand,
        requestFingerprint: String,
        now: Instant,
    ): CatalogCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(
            tx,
            command.ownerFingerprint,
            command.idempotencyKey,
            DELETE_REVIEW,
            OK_STATUS,
            requestFingerprint,
            now,
        ) {
            val workId = findOwnedReviewWork(tx, command.reviewId, command.memberId)
            lockWork(tx, workId)
            val existing = lockOwnedReview(tx, command.reviewId, command.memberId)
            val currentVersion = requireNotNull(existing.aggregateVersion)
            if (currentVersion != command.expectedVersion) {
                throw CatalogCommandConflictException(
                    "Review version is $currentVersion, not ${command.expectedVersion}",
                )
            }
            val nextVersion = Math.incrementExact(currentVersion)
            val previousState = reviewState(
                command.reviewId,
                workId,
                requireNotNull(existing.rating).toInt(),
                requireNotNull(existing.moderationStatus),
                false,
            )
            tx.deleteFrom(CATALOG_REVIEW)
                .where(
                    CATALOG_REVIEW.REVIEW_ID.eq(command.reviewId)
                        .and(CATALOG_REVIEW.AGGREGATE_VERSION.eq(currentVersion)),
                )
                .execute()
                .also { check(it == 1) { "Locked review delete was lost" } }
            refreshWorkRating(tx, workId, now)
            val state = previousState.toMutableMap().apply { this["deleted"] = true }
            persistAuditAndOutbox(
                tx,
                DELETE_REVIEW,
                REVIEW_AGGREGATE,
                command.reviewId,
                REVIEW_DELETED_EVENT,
                state,
                command.ownerFingerprint,
                DELETE_REVIEW_REASON,
                now,
                nextVersion,
                previousState,
            )
            CatalogCommandResult(REVIEW_AGGREGATE, command.reviewId, nextVersion, now)
        }
    }

    private fun findOwnedReviewWork(tx: DSLContext, reviewId: UUID, memberId: UUID): UUID =
        tx.select(CATALOG_REVIEW.WORK_ID)
            .from(CATALOG_REVIEW)
            .where(
                CATALOG_REVIEW.REVIEW_ID.eq(reviewId)
                    .and(CATALOG_REVIEW.MEMBER_ID.eq(memberId)),
            )
            .fetchOne(CATALOG_REVIEW.WORK_ID)
            ?: throw CatalogCommandNotFoundException("Review does not exist")

    private fun lockOwnedReview(tx: DSLContext, reviewId: UUID, memberId: UUID) = tx
        .selectFrom(CATALOG_REVIEW)
        .where(
            CATALOG_REVIEW.REVIEW_ID.eq(reviewId)
                .and(CATALOG_REVIEW.MEMBER_ID.eq(memberId)),
        )
        .forUpdate()
        .fetchOne()
        ?: throw CatalogCommandNotFoundException("Review does not exist")

    private fun lockWork(tx: DSLContext, workId: UUID) = tx.selectFrom(CATALOG_WORK)
        .where(CATALOG_WORK.WORK_ID.eq(workId))
        .forUpdate()
        .fetchOne()
        ?: throw CatalogCommandNotFoundException("Work does not exist")

    private fun refreshWorkRating(tx: DSLContext, workId: UUID, now: Instant) {
        val ratings = tx.select(CATALOG_REVIEW.RATING)
            .from(CATALOG_REVIEW)
            .where(
                CATALOG_REVIEW.WORK_ID.eq(workId)
                    .and(CATALOG_REVIEW.MODERATION_STATUS.eq(PUBLISHED_STATUS)),
            )
            .fetch(CATALOG_REVIEW.RATING)
        val average = if (ratings.isEmpty()) {
            BigDecimal.ZERO
        } else {
            ratings.fold(BigDecimal.ZERO) { sum, rating -> sum + BigDecimal.valueOf(rating.toLong()) }
                .divide(BigDecimal.valueOf(ratings.size.toLong()), 2, RoundingMode.HALF_UP)
        }
        tx.update(CATALOG_WORK)
            .set(CATALOG_WORK.RATING, average)
            .set(CATALOG_WORK.RATING_COUNT, ratings.size)
            .set(CATALOG_WORK.UPDATED_AT, now.toOffsetDateTime())
            .where(CATALOG_WORK.WORK_ID.eq(workId))
            .execute()
            .also { check(it == 1) { "Locked work rating update was lost" } }
    }

    private fun reviewState(
        reviewId: UUID,
        workId: UUID,
        rating: Int,
        moderationStatus: String,
        deleted: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "reviewId" to reviewId.toString(),
        "workId" to workId.toString(),
        "rating" to rating,
        "moderationStatus" to moderationStatus,
        "deleted" to deleted,
    )

    private fun replaceAuthors(tx: DSLContext, command: UpdateWorkCommand, now: Instant) {
        tx.deleteFrom(CATALOG_WORK_CONTRIBUTOR)
            .where(CATALOG_WORK_CONTRIBUTOR.WORK_ID.eq(command.workId))
            .execute()
        command.authors.forEachIndexed { index, author ->
            tx.insertInto(CATALOG_CONTRIBUTOR)
                .set(CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID, author.contributorId)
                .set(CATALOG_CONTRIBUTOR.NAME, author.name)
                .set(CATALOG_CONTRIBUTOR.BIOGRAPHY, author.bio)
                .set(CATALOG_CONTRIBUTOR.CREATED_AT, now.toOffsetDateTime())
                .set(CATALOG_CONTRIBUTOR.UPDATED_AT, now.toOffsetDateTime())
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
    }

    private fun workState(tx: DSLContext, workId: UUID): Map<String, Any?> {
        val work = tx.selectFrom(CATALOG_WORK)
            .where(CATALOG_WORK.WORK_ID.eq(workId))
            .fetchOne()
            ?: error("Locked work disappeared")
        val authors = tx.select(
            CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID,
            CATALOG_CONTRIBUTOR.NAME,
            CATALOG_CONTRIBUTOR.BIOGRAPHY,
        )
            .from(CATALOG_WORK_CONTRIBUTOR)
            .join(CATALOG_CONTRIBUTOR)
            .on(CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID.eq(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTOR_ID))
            .where(
                CATALOG_WORK_CONTRIBUTOR.WORK_ID.eq(workId)
                    .and(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTION_ROLE.eq(AUTHOR_ROLE)),
            )
            .orderBy(CATALOG_WORK_CONTRIBUTOR.DISPLAY_ORDER, CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID)
            .fetch { author ->
                linkedMapOf(
                    "contributorId" to requireNotNull(author[CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID]).toString(),
                    "name" to requireNotNull(author[CATALOG_CONTRIBUTOR.NAME]),
                    "bio" to author[CATALOG_CONTRIBUTOR.BIOGRAPHY],
                )
            }
        return linkedMapOf(
            "workId" to workId.toString(),
            "title" to requireNotNull(work.title),
            "summary" to requireNotNull(work.summary),
            "description" to requireNotNull(work.description),
            "genre" to requireNotNull(work.genre),
            "rating" to requireNotNull(work.rating).toDouble(),
            "authors" to authors,
        )
    }

    private fun lockEdition(tx: DSLContext, editionId: UUID): CatalogEditionRecord = tx
        .selectFrom(CATALOG_EDITION)
        .where(CATALOG_EDITION.EDITION_ID.eq(editionId))
        .forUpdate()
        .fetchOne()
        ?: throw CatalogCommandNotFoundException("Edition does not exist")

    private fun editionState(record: CatalogEditionRecord): Map<String, Any?> = editionState(
        editionId = requireNotNull(record.editionId),
        workId = requireNotNull(record.workId),
        title = requireNotNull(record.title),
        isbn = requireNotNull(record.isbn),
        publisher = requireNotNull(record.publisher),
        publicationYear = requireNotNull(record.publicationYear),
        language = requireNotNull(record.language),
        pageCount = requireNotNull(record.pageCount),
        coverUrl = record.coverUrl,
        coverColor = record.coverColor?.trim(),
        videoUrl = record.videoUrl,
        active = requireNotNull(record.isActive),
    )

    private fun editionState(
        editionId: UUID,
        workId: UUID,
        title: String,
        isbn: String,
        publisher: String,
        publicationYear: Int,
        language: String,
        pageCount: Int,
        coverUrl: String?,
        coverColor: String?,
        videoUrl: String?,
        active: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "editionId" to editionId.toString(),
        "workId" to workId.toString(),
        "title" to title,
        "isbn" to isbn,
        "publisher" to publisher,
        "publicationYear" to publicationYear,
        "language" to language,
        "pageCount" to pageCount,
        "coverUrl" to coverUrl,
        "coverColor" to coverColor,
        "videoUrl" to videoUrl,
        "isActive" to active,
    )

    private fun executeIdempotently(
        tx: DSLContext,
        ownerFingerprint: String,
        idempotencyKey: String,
        operation: String,
        responseStatus: Int,
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
            .set(CATALOG_COMMAND_IDEMPOTENCY.RESPONSE_STATUS, responseStatus)
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
        aggregateVersion: Long,
        previousState: Map<String, Any?>?,
    ) {
        val stateJson = JSON.valueOf(objectMapper.writeValueAsString(state))
        tx.insertInto(CATALOG_AUDIT_ENTRY)
            .set(CATALOG_AUDIT_ENTRY.AUDIT_ID, UUID.randomUUID())
            .set(CATALOG_AUDIT_ENTRY.AGGREGATE_TYPE, aggregateType)
            .set(CATALOG_AUDIT_ENTRY.AGGREGATE_ID, aggregateId)
            .set(CATALOG_AUDIT_ENTRY.AGGREGATE_VERSION, aggregateVersion)
            .set(CATALOG_AUDIT_ENTRY.OPERATION, operation)
            .set(CATALOG_AUDIT_ENTRY.ACTOR_FINGERPRINT, ownerFingerprint)
            .set(CATALOG_AUDIT_ENTRY.REASON, reason)
            .set(
                CATALOG_AUDIT_ENTRY.PREVIOUS_STATE,
                previousState?.let { JSON.valueOf(objectMapper.writeValueAsString(it)) },
            )
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
            .set(CATALOG_OUTBOX_EVENT.AGGREGATE_VERSION, aggregateVersion)
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
        const val UPDATE_WORK = "UPDATE_WORK"
        const val UPDATE_EDITION = "UPDATE_EDITION"
        const val SET_EDITION_ACTIVE = "SET_EDITION_ACTIVE"
        const val CREATE_REVIEW = "CREATE_REVIEW"
        const val UPDATE_REVIEW = "UPDATE_REVIEW"
        const val DELETE_REVIEW = "DELETE_REVIEW"
        const val WORK_AGGREGATE = "work"
        const val EDITION_AGGREGATE = "edition"
        const val REVIEW_AGGREGATE = "review"
        const val WORK_CREATED_EVENT = "catalog.work.created"
        const val EDITION_CREATED_EVENT = "catalog.edition.created"
        const val WORK_UPDATED_EVENT = "catalog.work.updated"
        const val EDITION_UPDATED_EVENT = "catalog.edition.updated"
        const val EDITION_ACTIVATION_CHANGED_EVENT = "catalog.edition.activation-changed"
        const val REVIEW_CREATED_EVENT = "catalog.review.created"
        const val REVIEW_UPDATED_EVENT = "catalog.review.updated"
        const val REVIEW_DELETED_EVENT = "catalog.review.deleted"
        const val PUBLISHED_STATUS = "PUBLISHED"
        const val RETURNED_STATUS = "RETURNED"
        const val CREATE_REVIEW_REASON = "Member created review"
        const val UPDATE_REVIEW_REASON = "Member updated review"
        const val DELETE_REVIEW_REASON = "Member deleted review"
        const val AUTHOR_ROLE = "AUTHOR"
        const val INITIAL_VERSION = 0L
        const val EVENT_VERSION = 1
        const val CREATED_STATUS = 201
        const val OK_STATUS = 200
        const val RETENTION_SECONDS = 86_400L * 7
        const val UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
