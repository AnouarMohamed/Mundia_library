package com.mundiapolis.library.catalog.adapter.outbound.persistence

import com.mundiapolis.library.catalog.dto.CatalogCommandConflictException
import com.mundiapolis.library.catalog.dto.CatalogCommandNotFoundException
import com.mundiapolis.library.catalog.dto.LearningResource
import com.mundiapolis.library.catalog.dto.LearningResourceImportCommand
import com.mundiapolis.library.catalog.dto.LearningResourceImportResult
import com.mundiapolis.library.catalog.dto.LearningResourcePage
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqLearningResourceRepository(private val dsl: DSLContext) {
    fun importBatch(
        command: LearningResourceImportCommand,
        manifestSha256: String,
        now: Instant,
    ): LearningResourceImportResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.execute("select pg_advisory_xact_lock(?)", lockKey(command.importId))

        val existingImport = tx.select(
            SOURCE_NAME, SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT,
            RECORD_COUNT, INSERTED_COUNT, UPDATED_COUNT, UNCHANGED_COUNT, COMPLETED_AT,
        ).from(IMPORT_TABLE).where(IMPORT_ID.eq(command.importId)).fetchOne()
        if (existingImport != null) {
            if (
                existingImport.get(MANIFEST_SHA256) != manifestSha256 ||
                existingImport.get(ACTOR_FINGERPRINT) != command.actorFingerprint
            ) {
                throw CatalogCommandConflictException("Import ID is already bound to different input or actor")
            }
            return@transactionResult existingImport.toImportResult(command.importId, replayed = true)
        }

        var inserted = 0
        var updated = 0
        var unchanged = 0
        val timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        command.items.forEach { item ->
            val existing = tx.select(RESOURCE_ID, CONTENT_SHA256, SOURCE_REVISION)
                .from(RESOURCE_TABLE)
                .where(SOURCE_NAME.eq(command.sourceName).and(SOURCE_RECORD_KEY.eq(item.sourceRecordKey)))
                .forUpdate()
                .fetchOne()
            if (existing == null) {
                if (tx.fetchExists(RESOURCE_TABLE, RESOURCE_ID.eq(item.resourceId))) {
                    throw CatalogCommandConflictException("Resource ID is already bound to another source record")
                }
                tx.insertInto(RESOURCE_TABLE)
                    .columns(
                        RESOURCE_ID, SOURCE_NAME, SOURCE_RECORD_KEY, TITLE, AUTHOR, DESCRIPTION,
                        CATEGORY, LANGUAGE, COVER_URL, COVER_ALT, SOURCE_URL, CONTENT_SHA256,
                        SOURCE_REVISION, IS_ACTIVE, CREATED_AT, UPDATED_AT,
                    )
                    .values(
                        item.resourceId, command.sourceName, item.sourceRecordKey, item.title,
                        item.author, item.description, item.category, item.language, item.coverUrl,
                        item.coverAlt, item.sourceUrl, item.contentSha256, command.sourceRevision,
                        true, timestamp, timestamp,
                    )
                    .execute()
                inserted++
            } else {
                if (existing.get(RESOURCE_ID) != item.resourceId) {
                    throw CatalogCommandConflictException("Source record is already bound to another resource ID")
                }
                if (
                    existing.get(CONTENT_SHA256) == item.contentSha256 &&
                    existing.get(SOURCE_REVISION) == command.sourceRevision
                ) {
                    unchanged++
                } else {
                    tx.update(RESOURCE_TABLE)
                        .set(TITLE, item.title)
                        .set(AUTHOR, item.author)
                        .set(DESCRIPTION, item.description)
                        .set(CATEGORY, item.category)
                        .set(LANGUAGE, item.language)
                        .set(COVER_URL, item.coverUrl)
                        .set(COVER_ALT, item.coverAlt)
                        .set(SOURCE_URL, item.sourceUrl)
                        .set(CONTENT_SHA256, item.contentSha256)
                        .set(SOURCE_REVISION, command.sourceRevision)
                        .set(IS_ACTIVE, true)
                        .set(UPDATED_AT, timestamp)
                        .where(RESOURCE_ID.eq(item.resourceId))
                        .execute()
                    updated++
                }
            }
        }

        tx.insertInto(IMPORT_TABLE)
            .columns(
                IMPORT_ID, SOURCE_NAME, SOURCE_REVISION, MANIFEST_SHA256, ACTOR_FINGERPRINT,
                RECORD_COUNT, INSERTED_COUNT, UPDATED_COUNT, UNCHANGED_COUNT, COMPLETED_AT,
            )
            .values(
                command.importId, command.sourceName, command.sourceRevision, manifestSha256,
                command.actorFingerprint, command.items.size, inserted, updated, unchanged, timestamp,
            )
            .execute()
        LearningResourceImportResult(
            command.importId, command.sourceName, command.sourceRevision, manifestSha256,
            command.items.size, inserted, updated, unchanged, now, replayed = false,
        )
    }

    fun importEvidence(importId: UUID): LearningResourceImportResult {
        val record = dsl.select(
            SOURCE_NAME, SOURCE_REVISION, MANIFEST_SHA256, RECORD_COUNT,
            INSERTED_COUNT, UPDATED_COUNT, UNCHANGED_COUNT, COMPLETED_AT,
        ).from(IMPORT_TABLE).where(IMPORT_ID.eq(importId)).fetchOne()
            ?: throw CatalogCommandNotFoundException("Learning-resource import does not exist")
        return record.toImportResult(importId, replayed = true)
    }

    fun find(resourceId: UUID): LearningResource? = dsl.select(
        RESOURCE_ID, TITLE, AUTHOR, DESCRIPTION, CATEGORY, LANGUAGE,
        COVER_URL, COVER_ALT, SOURCE_NAME, SOURCE_URL,
    ).from(RESOURCE_TABLE)
        .where(RESOURCE_ID.eq(resourceId).and(IS_ACTIVE.isTrue))
        .fetchOne()?.toResource()

    fun search(query: String?, category: String?, page: Int, limit: Int): LearningResourcePage {
        var condition: Condition = IS_ACTIVE.isTrue
        if (category != null) condition = condition.and(CATEGORY.eq(category))
        if (query != null) {
            condition = condition.and(
                DSL.condition(
                    "to_tsvector('simple', coalesce({0}, '') || ' ' || coalesce({1}, '') || ' ' || coalesce({2}, '') || ' ' || coalesce({3}, '')) @@ websearch_to_tsquery('simple', {4})",
                    TITLE, AUTHOR, CATEGORY, DESCRIPTION, DSL.`val`(query),
                ),
            )
        }
        val total = dsl.fetchCount(RESOURCE_TABLE, condition)
        val resources = dsl.select(
            RESOURCE_ID, TITLE, AUTHOR, DESCRIPTION, CATEGORY, LANGUAGE,
            COVER_URL, COVER_ALT, SOURCE_NAME, SOURCE_URL,
        ).from(RESOURCE_TABLE)
            .where(condition)
            .orderBy(TITLE.asc(), RESOURCE_ID.asc())
            .limit(limit)
            .offset(Math.multiplyExact(page, limit))
            .fetch { it.toResource() }
        val totalPages = if (total == 0) 0 else (total + limit - 1) / limit
        return LearningResourcePage(resources, total, page, totalPages)
    }

    fun categories(): List<String> = dsl.selectDistinct(CATEGORY)
        .from(RESOURCE_TABLE)
        .where(IS_ACTIVE.isTrue)
        .orderBy(CATEGORY.asc())
        .fetch(CATEGORY)

    private fun org.jooq.Record.toResource() = LearningResource(
        requireNotNull(get(RESOURCE_ID)), requireNotNull(get(TITLE)), get(AUTHOR), get(DESCRIPTION),
        requireNotNull(get(CATEGORY)), requireNotNull(get(LANGUAGE)), get(COVER_URL), get(COVER_ALT),
        requireNotNull(get(SOURCE_NAME)), requireNotNull(get(SOURCE_URL)),
    )

    private fun org.jooq.Record.toImportResult(importId: UUID, replayed: Boolean) =
        LearningResourceImportResult(
            importId, requireNotNull(get(SOURCE_NAME)), requireNotNull(get(SOURCE_REVISION)),
            requireNotNull(get(MANIFEST_SHA256)), requireNotNull(get(RECORD_COUNT)),
            requireNotNull(get(INSERTED_COUNT)), requireNotNull(get(UPDATED_COUNT)),
            requireNotNull(get(UNCHANGED_COUNT)), requireNotNull(get(COMPLETED_AT)).toInstant(), replayed,
        )

    private fun lockKey(id: UUID): Long = id.mostSignificantBits xor id.leastSignificantBits

    private companion object {
        val RESOURCE_TABLE = DSL.table(DSL.name("catalog_learning_resource"))
        val IMPORT_TABLE = DSL.table(DSL.name("catalog_learning_resource_import"))
        val RESOURCE_ID = DSL.field(DSL.name("resource_id"), UUID::class.java)
        val IMPORT_ID = DSL.field(DSL.name("import_id"), UUID::class.java)
        val SOURCE_NAME = DSL.field(DSL.name("source_name"), String::class.java)
        val SOURCE_RECORD_KEY = DSL.field(DSL.name("source_record_key"), String::class.java)
        val TITLE = DSL.field(DSL.name("title"), String::class.java)
        val AUTHOR = DSL.field(DSL.name("author"), String::class.java)
        val DESCRIPTION = DSL.field(DSL.name("description"), String::class.java)
        val CATEGORY = DSL.field(DSL.name("category"), String::class.java)
        val LANGUAGE = DSL.field(DSL.name("language"), String::class.java)
        val COVER_URL = DSL.field(DSL.name("cover_url"), String::class.java)
        val COVER_ALT = DSL.field(DSL.name("cover_alt"), String::class.java)
        val SOURCE_URL = DSL.field(DSL.name("source_url"), String::class.java)
        val CONTENT_SHA256 = DSL.field(DSL.name("content_sha256"), String::class.java)
        val SOURCE_REVISION = DSL.field(DSL.name("source_revision"), String::class.java)
        val IS_ACTIVE = DSL.field(DSL.name("is_active"), Boolean::class.java)
        val CREATED_AT = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)
        val UPDATED_AT = DSL.field(DSL.name("updated_at"), OffsetDateTime::class.java)
        val MANIFEST_SHA256 = DSL.field(DSL.name("manifest_sha256"), String::class.java)
        val ACTOR_FINGERPRINT = DSL.field(DSL.name("actor_fingerprint"), String::class.java)
        val RECORD_COUNT = DSL.field(DSL.name("record_count"), Int::class.java)
        val INSERTED_COUNT = DSL.field(DSL.name("inserted_count"), Int::class.java)
        val UPDATED_COUNT = DSL.field(DSL.name("updated_count"), Int::class.java)
        val UNCHANGED_COUNT = DSL.field(DSL.name("unchanged_count"), Int::class.java)
        val COMPLETED_AT = DSL.field(DSL.name("completed_at"), OffsetDateTime::class.java)
    }
}
