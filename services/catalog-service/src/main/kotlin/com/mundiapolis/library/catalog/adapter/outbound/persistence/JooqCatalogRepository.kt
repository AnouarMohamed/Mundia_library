package com.mundiapolis.library.catalog.adapter.outbound.persistence

import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONTRIBUTOR
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION_AVAILABILITY_PROJECTION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK_CONTRIBUTOR
import com.mundiapolis.library.catalog.dto.Author
import com.mundiapolis.library.catalog.dto.CatalogSearchFilters
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SortField
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqCatalogRepository(
    private val dsl: DSLContext,
) {
    private val totalCopiesValue =
        DSL.coalesce(CATALOG_EDITION_AVAILABILITY_PROJECTION.TOTAL_COPIES, 0)
    private val availableCopiesValue =
        DSL.coalesce(CATALOG_EDITION_AVAILABILITY_PROJECTION.AVAILABLE_COPIES, 0)
    private val totalCopies = totalCopiesValue.`as`("projected_total_copies")
    private val availableCopies = availableCopiesValue.`as`("projected_available_copies")

    fun findWork(workId: UUID): Work? = dsl
        .selectFrom(CATALOG_WORK)
        .where(CATALOG_WORK.WORK_ID.eq(workId))
        .fetchOne()
        ?.let { work ->
            Work(
                workId = requireNotNull(work.workId).toString(),
                title = requireNotNull(work.title),
                summary = requireNotNull(work.summary),
                description = requireNotNull(work.description),
                genre = requireNotNull(work.genre),
                rating = requireNotNull(work.rating).toDouble(),
                authors = findAuthors(workId),
            )
        }

    fun findEdition(editionId: UUID): Edition? = editionSelect()
        .where(CATALOG_EDITION.EDITION_ID.eq(editionId))
        .fetchOne()
        ?.let(::toEdition)

    fun search(filters: CatalogSearchFilters): CatalogSearchResult {
        val page = requireNotNull(filters.page)
        val limit = requireNotNull(filters.limit)
        val condition = searchCondition(filters)
        val total = dsl
            .selectCount()
            .from(CATALOG_EDITION)
            .join(CATALOG_WORK).on(CATALOG_WORK.WORK_ID.eq(CATALOG_EDITION.WORK_ID))
            .leftJoin(CATALOG_EDITION_AVAILABILITY_PROJECTION)
            .on(CATALOG_EDITION_AVAILABILITY_PROJECTION.EDITION_ID.eq(CATALOG_EDITION.EDITION_ID))
            .where(condition)
            .fetchOne(0, Int::class.java) ?: 0
        val editions = editionSelect()
            .where(condition)
            .orderBy(sortFields(filters.sortBy))
            .limit(limit)
            .offset(Math.multiplyExact(page, limit))
            .fetch(::toEdition)

        return CatalogSearchResult(
            editions = editions,
            total = total,
            page = page,
            totalPages = if (total == 0) 0 else (total + limit - 1) / limit,
        )
    }

    fun findDistinctGenres(): List<String> = dsl
        .selectDistinct(CATALOG_WORK.GENRE)
        .from(CATALOG_WORK)
        .join(CATALOG_EDITION).on(CATALOG_EDITION.WORK_ID.eq(CATALOG_WORK.WORK_ID))
        .where(CATALOG_EDITION.IS_ACTIVE.isTrue)
        .orderBy(CATALOG_WORK.GENRE.asc())
        .fetch(CATALOG_WORK.GENRE)
        .filterNotNull()

    private fun findAuthors(workId: UUID): List<Author> = dsl
        .select(
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
        .orderBy(
            CATALOG_WORK_CONTRIBUTOR.DISPLAY_ORDER.asc(),
            CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID.asc(),
        )
        .fetch { contributor ->
            Author(
                id = requireNotNull(contributor[CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID]).toString(),
                name = requireNotNull(contributor[CATALOG_CONTRIBUTOR.NAME]),
                bio = contributor[CATALOG_CONTRIBUTOR.BIOGRAPHY],
            )
        }

    private fun searchCondition(filters: CatalogSearchFilters): Condition {
        var condition = CATALOG_EDITION.IS_ACTIVE.isTrue
        filters.query?.let { query ->
            val pattern = "%${DSL.escape(query, '\\')}%"
            val contributorMatches = DSL.exists(
                dsl.selectOne()
                    .from(CATALOG_WORK_CONTRIBUTOR)
                    .join(CATALOG_CONTRIBUTOR)
                    .on(
                        CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID
                            .eq(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTOR_ID),
                    )
                    .where(
                        CATALOG_WORK_CONTRIBUTOR.WORK_ID.eq(CATALOG_WORK.WORK_ID)
                            .and(CATALOG_CONTRIBUTOR.NAME.likeIgnoreCase(pattern, '\\')),
                    ),
            )
            condition = condition.and(
                CATALOG_EDITION.TITLE.likeIgnoreCase(pattern, '\\')
                    .or(CATALOG_WORK.TITLE.likeIgnoreCase(pattern, '\\'))
                    .or(CATALOG_WORK.SUMMARY.likeIgnoreCase(pattern, '\\'))
                    .or(CATALOG_WORK.DESCRIPTION.likeIgnoreCase(pattern, '\\'))
                    .or(contributorMatches),
            )
        }
        filters.genre?.let { genre ->
            condition = condition.and(DSL.lower(CATALOG_WORK.GENRE).eq(genre.lowercase()))
        }
        filters.authorId?.let { authorId ->
            condition = condition.and(
                DSL.exists(
                    dsl.selectOne()
                        .from(CATALOG_WORK_CONTRIBUTOR)
                        .where(
                            CATALOG_WORK_CONTRIBUTOR.WORK_ID.eq(CATALOG_WORK.WORK_ID)
                                .and(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTOR_ID.eq(UUID.fromString(authorId)))
                                .and(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTION_ROLE.eq(AUTHOR_ROLE)),
                        ),
                ),
            )
        }
        if (filters.availableOnly == true) {
            condition = condition.and(availableCopiesValue.gt(0))
        }
        condition = condition.and(CATALOG_WORK.RATING.ge(requireNotNull(filters.minRating).toBigDecimal()))
        return condition
    }

    private fun sortFields(sortBy: String?): List<SortField<*>> = when (sortBy) {
        "rating" -> listOf(CATALOG_WORK.RATING.desc(), CATALOG_EDITION.EDITION_ID.asc())
        "publicationYear" ->
            listOf(CATALOG_EDITION.PUBLICATION_YEAR.desc(), CATALOG_EDITION.EDITION_ID.asc())
        else ->
            listOf(CATALOG_EDITION.TITLE.asc(), CATALOG_EDITION.EDITION_ID.asc())
    }

    private fun editionSelect() = dsl
        .select(
            CATALOG_EDITION.EDITION_ID,
            CATALOG_EDITION.WORK_ID,
            CATALOG_EDITION.TITLE,
            CATALOG_EDITION.ISBN,
            CATALOG_EDITION.PUBLISHER,
            CATALOG_EDITION.PUBLICATION_YEAR,
            CATALOG_EDITION.LANGUAGE,
            CATALOG_EDITION.PAGE_COUNT,
            CATALOG_EDITION.COVER_URL,
            CATALOG_EDITION.COVER_COLOR,
            CATALOG_EDITION.VIDEO_URL,
            CATALOG_EDITION.IS_ACTIVE,
            totalCopies,
            availableCopies,
        )
        .from(CATALOG_EDITION)
        .join(CATALOG_WORK).on(CATALOG_WORK.WORK_ID.eq(CATALOG_EDITION.WORK_ID))
        .leftJoin(CATALOG_EDITION_AVAILABILITY_PROJECTION)
        .on(CATALOG_EDITION_AVAILABILITY_PROJECTION.EDITION_ID.eq(CATALOG_EDITION.EDITION_ID))

    private fun toEdition(record: Record): Edition = Edition(
        editionId = requireNotNull(record[CATALOG_EDITION.EDITION_ID]).toString(),
        workId = requireNotNull(record[CATALOG_EDITION.WORK_ID]).toString(),
        title = requireNotNull(record[CATALOG_EDITION.TITLE]),
        isbn = requireNotNull(record[CATALOG_EDITION.ISBN]),
        publisher = requireNotNull(record[CATALOG_EDITION.PUBLISHER]),
        publicationYear = requireNotNull(record[CATALOG_EDITION.PUBLICATION_YEAR]),
        language = requireNotNull(record[CATALOG_EDITION.LANGUAGE]),
        pageCount = requireNotNull(record[CATALOG_EDITION.PAGE_COUNT]),
        coverUrl = record[CATALOG_EDITION.COVER_URL],
        coverColor = record[CATALOG_EDITION.COVER_COLOR]?.trim(),
        videoUrl = record[CATALOG_EDITION.VIDEO_URL],
        totalCopies = requireNotNull(record[totalCopies]),
        availableCopies = requireNotNull(record[availableCopies]),
        isActive = requireNotNull(record[CATALOG_EDITION.IS_ACTIVE]),
    )

    private companion object {
        const val AUTHOR_ROLE = "AUTHOR"
    }
}
