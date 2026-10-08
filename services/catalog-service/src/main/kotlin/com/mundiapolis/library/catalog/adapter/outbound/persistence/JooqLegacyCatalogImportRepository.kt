package com.mundiapolis.library.catalog.adapter.outbound.persistence

import com.mundiapolis.library.catalog.dto.CatalogCommandConflictException
import com.mundiapolis.library.catalog.dto.CatalogCommandNotFoundException
import com.mundiapolis.library.catalog.dto.LegacyCatalogImportCommand
import com.mundiapolis.library.catalog.dto.LegacyCatalogImportItem
import com.mundiapolis.library.catalog.dto.LegacyCatalogImportResult
import com.mundiapolis.library.catalog.dto.LegacyCatalogReviewImportItem
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqLegacyCatalogImportRepository(private val dsl: DSLContext) {
    fun importBatch(
        command: LegacyCatalogImportCommand,
        manifestSha256: String,
        completedAt: Instant,
    ): LegacyCatalogImportResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.execute("select pg_advisory_xact_lock(?)", lockKey(command.importId))
        val receipt = receipt(tx, command.importId)
        if (receipt != null) {
            if (
                receipt.get(MANIFEST_SHA256) != manifestSha256 ||
                receipt.get(ACTOR_FINGERPRINT) != command.actorFingerprint
            ) throw CatalogCommandConflictException("Import ID is bound to different input or actor")
            return@transactionResult receipt.toResult(command.importId, replayed = true)
        }

        command.items.sortedBy { it.editionId }.forEach { item -> importItem(tx, item) }
        val contributorCount = command.items.map { it.contributorId }.toSet().size
        val reviewCount = command.items.sumOf { it.reviews.size }
        val timestamp = completedAt.utc()
        tx.insertInto(IMPORT_TABLE)
            .columns(
                IMPORT_ID, SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT,
                WORK_COUNT, EDITION_COUNT, CONTRIBUTOR_COUNT, REVIEW_COUNT, COMPLETED_AT,
            )
            .values(
                command.importId, command.sourceRevision, manifestSha256, command.actorFingerprint,
                command.items.size, command.items.size, contributorCount, reviewCount, timestamp,
            )
            .execute()
        LegacyCatalogImportResult(
            importId = command.importId,
            sourceRevision = command.sourceRevision,
            manifestSha256 = manifestSha256,
            workCount = command.items.size,
            editionCount = command.items.size,
            contributorCount = contributorCount,
            reviewCount = reviewCount,
            completedAt = completedAt,
            replayed = false,
        )
    }

    fun importEvidence(importId: UUID): LegacyCatalogImportResult =
        receipt(dsl, importId)?.toResult(importId, replayed = true)
            ?: throw CatalogCommandNotFoundException("Legacy catalog import does not exist")

    private fun importItem(tx: DSLContext, item: LegacyCatalogImportItem) {
        val contributor = tx.select(CONTRIBUTOR_ID, CONTRIBUTOR_NAME, CONTRIBUTOR_BIOGRAPHY)
            .from(CONTRIBUTOR_TABLE).where(CONTRIBUTOR_ID.eq(item.contributorId)).forUpdate().fetchOne()
        if (contributor == null) {
            tx.insertInto(CONTRIBUTOR_TABLE)
                .columns(
                    CONTRIBUTOR_ID, CONTRIBUTOR_NAME, CONTRIBUTOR_BIOGRAPHY,
                    CONTRIBUTOR_CREATED_AT, CONTRIBUTOR_UPDATED_AT,
                )
                .values(item.contributorId, item.author, null, EPOCH, EPOCH)
                .execute()
        } else if (
            contributor.get(CONTRIBUTOR_NAME) != item.author ||
            contributor.get(CONTRIBUTOR_BIOGRAPHY) != null
        ) throw CatalogCommandConflictException("Contributor identity conflicts with imported metadata")

        val work = tx.select(
            WORK_ID, WORK_TITLE, WORK_SUMMARY, WORK_DESCRIPTION, WORK_GENRE,
            WORK_RATING, WORK_RATING_COUNT, WORK_VERSION, WORK_CREATED_AT, WORK_UPDATED_AT,
        ).from(WORK_TABLE).where(WORK_ID.eq(item.workId)).forUpdate().fetchOne()
        if (work == null) {
            tx.insertInto(WORK_TABLE)
                .columns(
                    WORK_ID, WORK_TITLE, WORK_SUMMARY, WORK_DESCRIPTION, WORK_GENRE,
                    WORK_RATING, WORK_RATING_COUNT, WORK_VERSION, WORK_CREATED_AT, WORK_UPDATED_AT,
                )
                .values(
                    item.workId, item.title, item.summary, item.description, item.genre,
                    BigDecimal.valueOf(item.rating), item.ratingCount, 0L,
                    item.createdAt.utc(), item.updatedAt.utc(),
                )
                .execute()
        } else if (!work.matchesWork(item)) {
            throw CatalogCommandConflictException("Work identity conflicts with imported metadata")
        }

        val link = tx.select(LINK_WORK_ID).from(LINK_TABLE)
            .where(
                LINK_WORK_ID.eq(item.workId)
                    .and(LINK_CONTRIBUTOR_ID.eq(item.contributorId))
                    .and(LINK_ROLE.eq(AUTHOR_ROLE)),
            ).fetchOne()
        if (link == null) {
            if (tx.fetchExists(LINK_TABLE, LINK_WORK_ID.eq(item.workId))) {
                throw CatalogCommandConflictException("Work already has a different contributor mapping")
            }
            tx.insertInto(LINK_TABLE)
                .columns(LINK_WORK_ID, LINK_CONTRIBUTOR_ID, LINK_ROLE, LINK_ORDER)
                .values(item.workId, item.contributorId, AUTHOR_ROLE, 0)
                .execute()
        }

        val edition = tx.select(
            EDITION_ID, EDITION_WORK_ID, EDITION_TITLE, EDITION_ISBN, EDITION_PUBLISHER,
            EDITION_YEAR, EDITION_LANGUAGE, EDITION_PAGE_COUNT, EDITION_COVER_URL,
            EDITION_COVER_COLOR, EDITION_VIDEO_URL, EDITION_ACTIVE, EDITION_VERSION,
            EDITION_CREATED_AT, EDITION_UPDATED_AT,
        ).from(EDITION_TABLE).where(EDITION_ID.eq(item.editionId)).forUpdate().fetchOne()
        if (edition == null) {
            if (tx.fetchExists(EDITION_TABLE, EDITION_ISBN.eq(item.isbn))) {
                throw CatalogCommandConflictException("ISBN is already bound to another edition")
            }
            tx.insertInto(EDITION_TABLE)
                .columns(
                    EDITION_ID, EDITION_WORK_ID, EDITION_TITLE, EDITION_ISBN, EDITION_PUBLISHER,
                    EDITION_YEAR, EDITION_LANGUAGE, EDITION_PAGE_COUNT, EDITION_COVER_URL,
                    EDITION_COVER_COLOR, EDITION_VIDEO_URL, EDITION_ACTIVE, EDITION_VERSION,
                    EDITION_CREATED_AT, EDITION_UPDATED_AT,
                )
                .values(
                    item.editionId, item.workId, item.title, item.isbn, item.publisher,
                    item.publicationYear, item.language, item.pageCount, item.coverUrl,
                    item.coverColor, item.videoUrl, item.active, 0L,
                    item.createdAt.utc(), item.updatedAt.utc(),
                )
                .execute()
        } else if (!edition.matchesEdition(item)) {
            throw CatalogCommandConflictException("Edition identity conflicts with imported metadata")
        }

        item.reviews.forEach { review -> importReview(tx, item.workId, review) }
    }

    private fun importReview(tx: DSLContext, workId: UUID, review: LegacyCatalogReviewImportItem) {
        val stored = tx.select(
            REVIEW_ID, REVIEW_WORK_ID, REVIEW_MEMBER_ID, REVIEW_RATING, REVIEW_CONTENT,
            REVIEW_STATUS, REVIEW_VERSION, REVIEW_CREATED_AT, REVIEW_UPDATED_AT,
        ).from(REVIEW_TABLE).where(REVIEW_ID.eq(review.reviewId)).forUpdate().fetchOne()
        if (stored == null) {
            if (tx.fetchExists(REVIEW_TABLE, REVIEW_WORK_ID.eq(workId).and(REVIEW_MEMBER_ID.eq(review.memberId)))) {
                throw CatalogCommandConflictException("Member already has a different review for the imported work")
            }
            tx.insertInto(REVIEW_TABLE)
                .columns(
                    REVIEW_ID, REVIEW_WORK_ID, REVIEW_MEMBER_ID, REVIEW_RATING, REVIEW_CONTENT,
                    REVIEW_STATUS, REVIEW_VERSION, REVIEW_CREATED_AT, REVIEW_UPDATED_AT,
                )
                .values(
                    review.reviewId, workId, review.memberId, review.rating.toShort(), review.content,
                    PUBLISHED_STATUS, 0L, review.createdAt.utc(), review.updatedAt.utc(),
                )
                .execute()
        } else if (
            stored.get(REVIEW_WORK_ID) != workId || stored.get(REVIEW_MEMBER_ID) != review.memberId ||
            stored.get(REVIEW_RATING)?.toInt() != review.rating || stored.get(REVIEW_CONTENT) != review.content ||
            stored.get(REVIEW_STATUS) != PUBLISHED_STATUS || stored.get(REVIEW_VERSION) != 0L ||
            stored.get(REVIEW_CREATED_AT)?.toInstant() != review.createdAt ||
            stored.get(REVIEW_UPDATED_AT)?.toInstant() != review.updatedAt
        ) throw CatalogCommandConflictException("Review identity conflicts with imported metadata")
    }

    private fun Record.matchesWork(item: LegacyCatalogImportItem): Boolean =
        get(WORK_TITLE) == item.title && get(WORK_SUMMARY) == item.summary &&
            get(WORK_DESCRIPTION) == item.description && get(WORK_GENRE) == item.genre &&
            get(WORK_RATING)?.compareTo(BigDecimal.valueOf(item.rating)) == 0 &&
            get(WORK_RATING_COUNT) == item.ratingCount && get(WORK_VERSION) == 0L &&
            get(WORK_CREATED_AT)?.toInstant() == item.createdAt && get(WORK_UPDATED_AT)?.toInstant() == item.updatedAt

    private fun Record.matchesEdition(item: LegacyCatalogImportItem): Boolean =
        get(EDITION_WORK_ID) == item.workId && get(EDITION_TITLE) == item.title &&
            get(EDITION_ISBN) == item.isbn && get(EDITION_PUBLISHER) == item.publisher &&
            get(EDITION_YEAR) == item.publicationYear && get(EDITION_LANGUAGE) == item.language &&
            get(EDITION_PAGE_COUNT) == item.pageCount && get(EDITION_COVER_URL) == item.coverUrl &&
            get(EDITION_COVER_COLOR)?.trimEnd() == item.coverColor && get(EDITION_VIDEO_URL) == item.videoUrl &&
            get(EDITION_ACTIVE) == item.active && get(EDITION_VERSION) == 0L &&
            get(EDITION_CREATED_AT)?.toInstant() == item.createdAt &&
            get(EDITION_UPDATED_AT)?.toInstant() == item.updatedAt

    private fun receipt(context: DSLContext, importId: UUID): Record? = context.select(
        SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT, WORK_COUNT,
        EDITION_COUNT, CONTRIBUTOR_COUNT, REVIEW_COUNT, COMPLETED_AT,
    ).from(IMPORT_TABLE).where(IMPORT_ID.eq(importId)).fetchOne()

    private fun Record.toResult(importId: UUID, replayed: Boolean) = LegacyCatalogImportResult(
        importId, requireNotNull(get(SOURCE_REVISION)), requireNotNull(get(MANIFEST_SHA256)),
        requireNotNull(get(WORK_COUNT)), requireNotNull(get(EDITION_COUNT)),
        requireNotNull(get(CONTRIBUTOR_COUNT)), requireNotNull(get(REVIEW_COUNT)),
        requireNotNull(get(COMPLETED_AT)).toInstant(), replayed,
    )

    private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
    private fun lockKey(id: UUID): Long = id.mostSignificantBits xor id.leastSignificantBits

    private companion object {
        const val AUTHOR_ROLE = "AUTHOR"
        const val PUBLISHED_STATUS = "PUBLISHED"
        val EPOCH: OffsetDateTime = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)
        fun table(name: String): Table<Record> = DSL.table(DSL.name(name))
        fun <T> field(name: String, type: Class<T>): Field<T> = DSL.field(DSL.name(name), type)
        val IMPORT_TABLE = table("catalog_legacy_import")
        val WORK_TABLE = table("catalog_work")
        val EDITION_TABLE = table("catalog_edition")
        val CONTRIBUTOR_TABLE = table("catalog_contributor")
        val LINK_TABLE = table("catalog_work_contributor")
        val REVIEW_TABLE = table("catalog_review")
        val IMPORT_ID = field("import_id", UUID::class.java)
        val SOURCE_REVISION = field("source_revision", String::class.java)
        val MANIFEST_SHA256 = field("manifest_sha256", String::class.java)
        val ACTOR_FINGERPRINT = field("actor_fingerprint", String::class.java)
        val WORK_COUNT = field("work_count", Int::class.java)
        val EDITION_COUNT = field("edition_count", Int::class.java)
        val CONTRIBUTOR_COUNT = field("contributor_count", Int::class.java)
        val REVIEW_COUNT = field("review_count", Int::class.java)
        val COMPLETED_AT = field("completed_at", OffsetDateTime::class.java)
        val WORK_ID = field("work_id", UUID::class.java)
        val WORK_TITLE = field("title", String::class.java)
        val WORK_SUMMARY = field("summary", String::class.java)
        val WORK_DESCRIPTION = field("description", String::class.java)
        val WORK_GENRE = field("genre", String::class.java)
        val WORK_RATING = field("rating", BigDecimal::class.java)
        val WORK_RATING_COUNT = field("rating_count", Int::class.java)
        val WORK_VERSION = field("aggregate_version", Long::class.java)
        val WORK_CREATED_AT = field("created_at", OffsetDateTime::class.java)
        val WORK_UPDATED_AT = field("updated_at", OffsetDateTime::class.java)
        val CONTRIBUTOR_ID = field("contributor_id", UUID::class.java)
        val CONTRIBUTOR_NAME = field("name", String::class.java)
        val CONTRIBUTOR_BIOGRAPHY = field("biography", String::class.java)
        val CONTRIBUTOR_CREATED_AT = field("created_at", OffsetDateTime::class.java)
        val CONTRIBUTOR_UPDATED_AT = field("updated_at", OffsetDateTime::class.java)
        val LINK_WORK_ID = field("work_id", UUID::class.java)
        val LINK_CONTRIBUTOR_ID = field("contributor_id", UUID::class.java)
        val LINK_ROLE = field("contribution_role", String::class.java)
        val LINK_ORDER = field("display_order", Int::class.java)
        val EDITION_ID = field("edition_id", UUID::class.java)
        val EDITION_WORK_ID = field("work_id", UUID::class.java)
        val EDITION_TITLE = field("title", String::class.java)
        val EDITION_ISBN = field("isbn", String::class.java)
        val EDITION_PUBLISHER = field("publisher", String::class.java)
        val EDITION_YEAR = field("publication_year", Int::class.java)
        val EDITION_LANGUAGE = field("language", String::class.java)
        val EDITION_PAGE_COUNT = field("page_count", Int::class.java)
        val EDITION_COVER_URL = field("cover_url", String::class.java)
        val EDITION_COVER_COLOR = field("cover_color", String::class.java)
        val EDITION_VIDEO_URL = field("video_url", String::class.java)
        val EDITION_ACTIVE = field("is_active", Boolean::class.java)
        val EDITION_VERSION = field("aggregate_version", Long::class.java)
        val EDITION_CREATED_AT = field("created_at", OffsetDateTime::class.java)
        val EDITION_UPDATED_AT = field("updated_at", OffsetDateTime::class.java)
        val REVIEW_ID = field("review_id", UUID::class.java)
        val REVIEW_WORK_ID = field("work_id", UUID::class.java)
        val REVIEW_MEMBER_ID = field("member_id", UUID::class.java)
        val REVIEW_RATING = field("rating", Short::class.java)
        val REVIEW_CONTENT = field("content", String::class.java)
        val REVIEW_STATUS = field("moderation_status", String::class.java)
        val REVIEW_VERSION = field("aggregate_version", Long::class.java)
        val REVIEW_CREATED_AT = field("created_at", OffsetDateTime::class.java)
        val REVIEW_UPDATED_AT = field("updated_at", OffsetDateTime::class.java)
    }
}
