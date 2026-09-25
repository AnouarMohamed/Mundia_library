package com.mundiapolis.library.catalog

import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONTRIBUTOR
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_AUDIT_ENTRY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_COMMAND_IDEMPOTENCY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONSUMER_INBOX
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_COPY_AVAILABILITY_PROJECTION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION_AVAILABILITY_PROJECTION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_OUTBOX_EVENT
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_REVIEW
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK_CONTRIBUTOR
import com.mundiapolis.library.catalog.dto.CatalogAuthorInput
import com.mundiapolis.library.catalog.dto.BrokerAcknowledgement
import com.mundiapolis.library.catalog.dto.CatalogOutboxFailureCode
import com.mundiapolis.library.catalog.dto.CatalogOutboxFailureDisposition
import com.mundiapolis.library.catalog.dto.CreateWorkCommand
import com.mundiapolis.library.catalog.dto.UpdateWorkCommand
import com.mundiapolis.library.catalog.dto.CirculationCopyEvent
import com.mundiapolis.library.catalog.dto.CirculationEventConflictException
import com.mundiapolis.library.catalog.dto.CirculationEventGapException
import com.mundiapolis.library.catalog.dto.ConsumerEventDisposition
import com.mundiapolis.library.catalog.dto.ProjectedCopyStatus
import com.mundiapolis.library.catalog.service.CatalogOutboxStore
import com.mundiapolis.library.catalog.service.CatalogCommandService
import com.mundiapolis.library.catalog.service.CirculationAvailabilityProjectionService
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogServiceIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var commandService: CatalogCommandService

    @Autowired
    private lateinit var outboxStore: CatalogOutboxStore

    @Autowired
    private lateinit var availabilityProjectionService: CirculationAvailabilityProjectionService

    @BeforeEach
    fun seedCatalog() {
        dsl.deleteFrom(CATALOG_OUTBOX_EVENT).execute()
        dsl.deleteFrom(CATALOG_AUDIT_ENTRY).execute()
        dsl.deleteFrom(CATALOG_COMMAND_IDEMPOTENCY).execute()
        dsl.deleteFrom(CATALOG_REVIEW).execute()
        dsl.deleteFrom(CATALOG_CONSUMER_INBOX).execute()
        dsl.deleteFrom(CATALOG_COPY_AVAILABILITY_PROJECTION).execute()
        dsl.deleteFrom(CATALOG_EDITION_AVAILABILITY_PROJECTION).execute()
        dsl.deleteFrom(CATALOG_EDITION).execute()
        dsl.deleteFrom(CATALOG_WORK_CONTRIBUTOR).execute()
        dsl.deleteFrom(CATALOG_CONTRIBUTOR).execute()
        dsl.deleteFrom(CATALOG_WORK).execute()

        insertContributor(PRIMARY_AUTHOR_ID, "Maya Author", "Primary biography")
        insertContributor(SECONDARY_AUTHOR_ID, "Zayd Writer", null)
        insertWork(FICTION_WORK_ID, "Atlas of Quiet Rooms", "A hidden archive", "Fiction", "4.75")
        insertWork(SCIENCE_WORK_ID, "Botany at Dusk", "Field observations", "Science", "3.50")
        linkAuthor(FICTION_WORK_ID, SECONDARY_AUTHOR_ID, 1)
        linkAuthor(FICTION_WORK_ID, PRIMARY_AUTHOR_ID, 0)
        linkAuthor(SCIENCE_WORK_ID, SECONDARY_AUTHOR_ID, 0)
        insertEdition(
            AVAILABLE_EDITION_ID,
            FICTION_WORK_ID,
            "Atlas of Quiet Rooms",
            "9780000000001",
            2026,
            active = true,
            coverUrl = "https://images.example.test/atlas.jpg",
        )
        insertEdition(
            UNAVAILABLE_EDITION_ID,
            SCIENCE_WORK_ID,
            "Botany at Dusk",
            "9780000000002",
            2024,
            active = true,
            coverUrl = null,
        )
        insertEdition(
            INACTIVE_EDITION_ID,
            FICTION_WORK_ID,
            "Archived Atlas",
            "9780000000003",
            2010,
            active = false,
            coverUrl = null,
        )
        insertAvailability(AVAILABLE_EDITION_ID, total = 4, available = 2, version = 8)
        insertAvailability(UNAVAILABLE_EDITION_ID, total = 3, available = 0, version = 5)
        insertReview(
            PUBLISHED_REVIEW_ID,
            FICTION_WORK_ID,
            UUID.fromString("50000000-0000-0000-0000-000000000001"),
            rating = 5,
            content = "Careful and memorable.",
            status = "PUBLISHED",
            createdAt = NOW.minusDays(1),
        )
        insertReview(
            OLDER_REVIEW_ID,
            FICTION_WORK_ID,
            UUID.fromString("50000000-0000-0000-0000-000000000002"),
            rating = 4,
            content = "Strong archival detail.",
            status = "PUBLISHED",
            createdAt = NOW.minusDays(2),
        )
        insertReview(
            HIDDEN_REVIEW_ID,
            FICTION_WORK_ID,
            UUID.fromString("50000000-0000-0000-0000-000000000003"),
            rating = 1,
            content = "Hidden moderation fixture.",
            status = "HIDDEN",
            createdAt = NOW,
        )
    }

    @Test
    fun `work reads authoritative metadata with authors in display order`() {
        mockMvc.perform(
            get("/api/v1/catalog/works/$FICTION_WORK_ID").with(scope(READ_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workId").value(FICTION_WORK_ID.toString()))
            .andExpect(jsonPath("$.rating").value(4.75))
            .andExpect(jsonPath("$.authors[0].id").value(PRIMARY_AUTHOR_ID.toString()))
            .andExpect(jsonPath("$.authors[0].bio").value("Primary biography"))
            .andExpect(jsonPath("$.authors[1].id").value(SECONDARY_AUTHOR_ID.toString()))
            .andExpect(jsonPath("$.authors[1].bio").doesNotExist())
    }

    @Test
    fun `edition reads include the latest disposable availability projection`() {
        mockMvc.perform(
            get("/api/v1/catalog/editions/$AVAILABLE_EDITION_ID").with(scope(READ_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalCopies").value(4))
            .andExpect(jsonPath("$.availableCopies").value(2))
            .andExpect(jsonPath("$.coverColor").value("#123ABC"))

        mockMvc.perform(
            get("/api/v1/catalog/editions/$INACTIVE_EDITION_ID").with(scope(READ_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalCopies").value(0))
            .andExpect(jsonPath("$.availableCopies").value(0))
            .andExpect(jsonPath("$.coverUrl").doesNotExist())
    }

    @Test
    fun `copy events atomically project availability with replay and ordering guards`() {
        val copyId = UUID.randomUUID()
        val registered = circulationCopyEvent(
            copyId = copyId,
            version = 0,
            status = ProjectedCopyStatus.AVAILABLE,
        )

        val first = availabilityProjectionService.apply(registered)
        val replay = availabilityProjectionService.apply(registered)

        assertEquals(ConsumerEventDisposition.APPLIED, first.disposition)
        assertEquals(false, first.replayed)
        assertEquals(true, replay.replayed)
        assertEquals(1, dsl.fetchCount(CATALOG_CONSUMER_INBOX))
        assertEquals(1, dsl.fetchCount(CATALOG_COPY_AVAILABILITY_PROJECTION))
        assertAvailability(total = 1, available = 1)

        val checkedOut = circulationCopyEvent(
            copyId = copyId,
            version = 1,
            status = ProjectedCopyStatus.ON_LOAN,
        )
        availabilityProjectionService.apply(checkedOut)

        assertAvailability(total = 1, available = 0)
        assertEquals(
            "ON_LOAN",
            dsl.select(CATALOG_COPY_AVAILABILITY_PROJECTION.STATUS)
                .from(CATALOG_COPY_AVAILABILITY_PROJECTION)
                .where(CATALOG_COPY_AVAILABILITY_PROJECTION.COPY_ID.eq(copyId))
                .fetchSingle(CATALOG_COPY_AVAILABILITY_PROJECTION.STATUS),
        )

        val additionalCopies = listOf(UUID.randomUUID(), UUID.randomUUID())
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.invokeAll(
                additionalCopies.map { additionalCopyId ->
                    Callable {
                        availabilityProjectionService.apply(
                            circulationCopyEvent(
                                additionalCopyId,
                                version = 0,
                                status = ProjectedCopyStatus.AVAILABLE,
                            ),
                        )
                    }
                },
            ).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertAvailability(total = 3, available = 2)

        assertThrows(CirculationEventGapException::class.java) {
            availabilityProjectionService.apply(
                circulationCopyEvent(copyId, version = 3, status = ProjectedCopyStatus.AVAILABLE),
            )
        }
        assertThrows(CirculationEventConflictException::class.java) {
            availabilityProjectionService.apply(
                registered.copy(payloadSha256 = "f".repeat(64)),
            )
        }
        assertEquals(4, dsl.fetchCount(CATALOG_CONSUMER_INBOX))
        assertAvailability(total = 3, available = 2)
    }

    @Test
    fun `review reads expose only published content without member identifiers`() {
        mockMvc.perform(
            get("/api/v1/catalog/works/$FICTION_WORK_ID/reviews")
                .param("limit", "1")
                .with(scope(READ_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.reviews[0].reviewId").value(PUBLISHED_REVIEW_ID.toString()))
            .andExpect(jsonPath("$.reviews[0].rating").value(5))
            .andExpect(jsonPath("$.reviews[0].content").value("Careful and memorable."))
            .andExpect(jsonPath("$.reviews[0].reviewerLabel").value("Verified reader"))
            .andExpect(jsonPath("$.reviews[0].memberId").doesNotExist())
            .andExpect(jsonPath("$.reviews[0].moderationStatus").doesNotExist())

        mockMvc.perform(
            get("/api/v1/catalog/works/$FICTION_WORK_ID/reviews")
                .param("page", "1")
                .param("limit", "1")
                .with(scope(READ_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reviews[0].reviewId").value(OLDER_REVIEW_ID.toString()))

        mockMvc.perform(
            get("/api/v1/catalog/works/$FICTION_WORK_ID/reviews")
                .param("limit", "0")
                .with(scope(READ_SCOPE)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `search applies text author genre rating and availability filters in SQL`() {
        mockMvc.perform(
            get("/api/v1/catalog/search")
                .param("query", "maya")
                .param("authorId", PRIMARY_AUTHOR_ID.toString())
                .param("genre", "fiction")
                .param("minRating", "4.5")
                .param("availableOnly", "true")
                .with(scope(SEARCH_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.editions[0].editionId").value(AVAILABLE_EDITION_ID.toString()))

        mockMvc.perform(
            get("/api/v1/catalog/search")
                .param("availableOnly", "true")
                .param("minRating", "5")
                .with(scope(SEARCH_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.totalPages").value(0))
    }

    @Test
    fun `search excludes inactive editions and paginates with a stable tie breaker`() {
        mockMvc.perform(
            get("/api/v1/catalog/search")
                .param("sortBy", "publicationYear")
                .param("page", "1")
                .param("limit", "1")
                .with(scope(SEARCH_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.editions[0].editionId").value(UNAVAILABLE_EDITION_ID.toString()))

        mockMvc.perform(get("/api/v1/catalog/genres").with(scope(SEARCH_SCOPE)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0]").value("Fiction"))
            .andExpect(jsonPath("$[1]").value("Science"))
    }

    @Test
    fun `invalid search input is rejected and missing records return not found`() {
        mockMvc.perform(
            get("/api/v1/catalog/search")
                .param("limit", "0")
                .with(scope(SEARCH_SCOPE)),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            get("/api/v1/catalog/works/${UUID.randomUUID()}").with(scope(READ_SCOPE)),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `protected reads enforce authentication and endpoint scopes`() {
        mockMvc.perform(get("/api/v1/catalog/editions/$AVAILABLE_EDITION_ID"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/api/v1/catalog/search").with(scope(READ_SCOPE)),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `health probes and the published contract remain public`() {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/openapi/catalog-v1.json"))
            .andExpect(status().isOk)
    }

    @Test
    fun `work creation commits metadata audit outbox and exact idempotent response atomically`() {
        val workId = UUID.fromString("40000000-0000-0000-0000-000000000001")
        val contributorId = UUID.fromString("41000000-0000-0000-0000-000000000001")
        val body = createWorkBody(workId, contributorId, "Created Through Command")

        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-work-key-0001")
                .content(body)
                .with(commandScope()),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(header().string("Location", "/api/v1/catalog/works/$workId"))
            .andExpect(jsonPath("$.aggregateType").value("work"))
            .andExpect(jsonPath("$.aggregateVersion").value(0))

        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-work-key-0001")
                .content(body)
                .with(commandScope()),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.aggregateId").value(workId.toString()))

        assertEquals(1, dsl.fetchCount(CATALOG_WORK, CATALOG_WORK.WORK_ID.eq(workId)))
        assertEquals(
            1,
            dsl.fetchCount(CATALOG_WORK_CONTRIBUTOR, CATALOG_WORK_CONTRIBUTOR.WORK_ID.eq(workId)),
        )
        assertEquals(1, dsl.fetchCount(CATALOG_AUDIT_ENTRY, CATALOG_AUDIT_ENTRY.AGGREGATE_ID.eq(workId)))
        assertEquals(1, dsl.fetchCount(CATALOG_OUTBOX_EVENT, CATALOG_OUTBOX_EVENT.AGGREGATE_ID.eq(workId)))
        assertEquals(
            "catalog.work.created",
            dsl.select(CATALOG_OUTBOX_EVENT.EVENT_TYPE)
                .from(CATALOG_OUTBOX_EVENT)
                .where(CATALOG_OUTBOX_EVENT.AGGREGATE_ID.eq(workId))
                .fetchOne(CATALOG_OUTBOX_EVENT.EVENT_TYPE),
        )
    }

    @Test
    fun `edition creation never accepts or fabricates physical inventory`() {
        val editionId = UUID.fromString("42000000-0000-0000-0000-000000000001")
        val body = """
            {
              "editionId": "$editionId",
              "title": "Command Edition",
              "isbn": "9780000000042",
              "publisher": "Mundiapolis Press",
              "publicationYear": 2026,
              "language": "English",
              "pageCount": 320,
              "coverUrl": "https://images.example.test/command.jpg",
              "coverColor": "#abcdef",
              "videoUrl": null,
              "isActive": true,
              "reason": "Initial reviewed catalog import"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/v1/catalog/works/$FICTION_WORK_ID/editions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-edition-key-01")
                .content(body)
                .with(commandScope()),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.aggregateType").value("edition"))

        assertEquals(1, dsl.fetchCount(CATALOG_EDITION, CATALOG_EDITION.EDITION_ID.eq(editionId)))
        assertEquals(
            0,
            dsl.fetchCount(
                CATALOG_EDITION_AVAILABILITY_PROJECTION,
                CATALOG_EDITION_AVAILABILITY_PROJECTION.EDITION_ID.eq(editionId),
            ),
        )
        assertEquals(
            "#ABCDEF",
            dsl.select(CATALOG_EDITION.COVER_COLOR)
                .from(CATALOG_EDITION)
                .where(CATALOG_EDITION.EDITION_ID.eq(editionId))
                .fetchOne(CATALOG_EDITION.COVER_COLOR)
                ?.trim(),
        )

        val forbiddenInventory = body
            .replace(editionId.toString(), UUID.randomUUID().toString())
            .replace("\"reason\":", "\"totalCopies\": 5, \"reason\":")
        mockMvc.perform(
            post("/api/v1/catalog/works/$FICTION_WORK_ID/editions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-edition-key-02")
                .content(forbiddenInventory)
                .with(commandScope()),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `catalog commands reject key reuse changed input invalid actor and wrong scope`() {
        val workId = UUID.fromString("43000000-0000-0000-0000-000000000001")
        val contributorId = UUID.fromString("44000000-0000-0000-0000-000000000001")
        val original = createWorkBody(workId, contributorId, "Original Title")
        val changed = createWorkBody(workId, contributorId, "Changed Title")

        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-conflict-key")
                .content(original)
                .with(commandScope()),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-conflict-key")
                .content(changed)
                .with(commandScope()),
        ).andExpect(status().isConflict)

        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-invalid-actor")
                .content(createWorkBody(UUID.randomUUID(), UUID.randomUUID(), "Missing Actor"))
                .with(jwt().authorities(SimpleGrantedAuthority(MANAGE_SCOPE))),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-wrong-scope1")
                .content(createWorkBody(UUID.randomUUID(), UUID.randomUUID(), "Wrong Scope"))
                .with(scope(READ_SCOPE)),
        ).andExpect(status().isForbidden)

        val rolledBackWorkId = UUID.fromString("47000000-0000-0000-0000-000000000001")
        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-atomic-rollback")
                .content(createWorkBody(rolledBackWorkId, PRIMARY_AUTHOR_ID, "Conflicting Contributor"))
                .with(commandScope()),
        ).andExpect(status().isConflict)
        assertEquals(0, dsl.fetchCount(CATALOG_WORK, CATALOG_WORK.WORK_ID.eq(rolledBackWorkId)))
        assertEquals(
            0,
            dsl.fetchCount(
                CATALOG_COMMAND_IDEMPOTENCY,
                CATALOG_COMMAND_IDEMPOTENCY.IDEMPOTENCY_KEY.eq("catalog-atomic-rollback"),
            ),
        )
        assertEquals(
            0,
            dsl.fetchCount(CATALOG_OUTBOX_EVENT, CATALOG_OUTBOX_EVENT.AGGREGATE_ID.eq(rolledBackWorkId)),
        )

        mockMvc.perform(
            post("/api/v1/catalog/works")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-oversized-key")
                .content("x".repeat(128 * 1024 + 1))
                .with(commandScope()),
        ).andExpect(status().isContentTooLarge)
    }

    @Test
    fun `concurrent exact retries create one aggregate audit and outbox event`() {
        val workId = UUID.fromString("45000000-0000-0000-0000-000000000001")
        val command = CreateWorkCommand(
            workId = workId,
            title = "Concurrent Catalog Work",
            summary = "A concurrency test",
            description = "A reviewed concurrency test description",
            genre = "Systems",
            authors = listOf(
                CatalogAuthorInput(
                    contributorId = UUID.fromString("46000000-0000-0000-0000-000000000001"),
                    name = "Concurrent Author",
                    bio = null,
                ),
            ),
            reason = "Verify exact concurrent command replay",
            idempotencyKey = "catalog-concurrent-key",
            ownerFingerprint = "a".repeat(64),
        )
        val executor = Executors.newFixedThreadPool(10)
        val results = try {
            executor.invokeAll(
                (1..20).map {
                    Callable { commandService.createWork(command) }
                },
            ).map { future -> future.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { !it.replayed })
        assertEquals(19, results.count { it.replayed })
        assertEquals(1, results.map { it.result }.toSet().size)
        assertEquals(1, dsl.fetchCount(CATALOG_WORK, CATALOG_WORK.WORK_ID.eq(workId)))
        assertEquals(1, dsl.fetchCount(CATALOG_AUDIT_ENTRY, CATALOG_AUDIT_ENTRY.AGGREGATE_ID.eq(workId)))
        assertEquals(1, dsl.fetchCount(CATALOG_OUTBOX_EVENT, CATALOG_OUTBOX_EVENT.AGGREGATE_ID.eq(workId)))
    }

    @Test
    fun `work updates require an exact version and replay after the version advances`() {
        val replacementAuthor = UUID.fromString("48000000-0000-0000-0000-000000000001")
        val body = """
            {
              "title": "Atlas of Revised Rooms",
              "summary": "A revised summary",
              "description": "A reviewed revised description",
              "genre": "Architecture",
              "authors": [{
                "contributorId": "$replacementAuthor",
                "name": "Revision Author",
                "bio": null
              }],
              "reason": "Correct catalog metadata after review"
            }
        """.trimIndent()

        repeat(2) { attempt ->
            mockMvc.perform(
                put("/api/v1/catalog/works/$FICTION_WORK_ID")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("If-Match", "\"0\"")
                    .header("Idempotency-Key", "catalog-work-update01")
                    .content(body)
                    .with(commandScope()),
            )
                .andExpect(status().isOk)
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(
                    header().string("Idempotency-Replayed", (attempt == 1).toString()),
                )
                .andExpect(jsonPath("$.aggregateVersion").value(1))
        }

        mockMvc.perform(
            put("/api/v1/catalog/works/$FICTION_WORK_ID")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "catalog-stale-update1")
                .content(body)
                .with(commandScope()),
        ).andExpect(status().isConflict)

        assertEquals(
            "Atlas of Revised Rooms",
            dsl.select(CATALOG_WORK.TITLE)
                .from(CATALOG_WORK)
                .where(CATALOG_WORK.WORK_ID.eq(FICTION_WORK_ID))
                .fetchOne(CATALOG_WORK.TITLE),
        )
        assertEquals(
            listOf(replacementAuthor),
            dsl.select(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTOR_ID)
                .from(CATALOG_WORK_CONTRIBUTOR)
                .where(CATALOG_WORK_CONTRIBUTOR.WORK_ID.eq(FICTION_WORK_ID))
                .fetch(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTOR_ID),
        )
        assertEquals(
            "catalog.work.updated",
            dsl.select(CATALOG_OUTBOX_EVENT.EVENT_TYPE)
                .from(CATALOG_OUTBOX_EVENT)
                .where(CATALOG_OUTBOX_EVENT.AGGREGATE_ID.eq(FICTION_WORK_ID))
                .fetchOne(CATALOG_OUTBOX_EVENT.EVENT_TYPE),
        )
    }

    @Test
    fun `edition metadata and activation advance one version without changing availability`() {
        val updateBody = """
            {
              "title": "Atlas Revised Edition",
              "isbn": "9780000000099",
              "publisher": "Mundiapolis Academic Press",
              "publicationYear": 2027,
              "language": "French",
              "pageCount": 360,
              "coverUrl": null,
              "coverColor": "#FEDCBA",
              "videoUrl": null,
              "reason": "Apply reviewed edition metadata corrections"
            }
        """.trimIndent()
        mockMvc.perform(
            put("/api/v1/catalog/editions/$AVAILABLE_EDITION_ID")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "catalog-edition-update")
                .content(updateBody)
                .with(commandScope()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))

        mockMvc.perform(
            post("/api/v1/catalog/editions/$AVAILABLE_EDITION_ID/activation")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"1\"")
                .header("Idempotency-Key", "catalog-edition-active")
                .content(
                    """{"isActive":false,"reason":"Withdraw damaged edition from catalog"}""",
                )
                .with(commandScope()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.aggregateVersion").value(2))

        mockMvc.perform(
            get("/api/v1/catalog/search").param("query", "Atlas").with(scope(SEARCH_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))

        assertEquals(
            2,
            dsl.select(CATALOG_EDITION_AVAILABILITY_PROJECTION.AVAILABLE_COPIES)
                .from(CATALOG_EDITION_AVAILABILITY_PROJECTION)
                .where(CATALOG_EDITION_AVAILABILITY_PROJECTION.EDITION_ID.eq(AVAILABLE_EDITION_ID))
                .fetchOne(CATALOG_EDITION_AVAILABILITY_PROJECTION.AVAILABLE_COPIES),
        )
        assertEquals(
            listOf("catalog.edition.updated", "catalog.edition.activation-changed"),
            dsl.select(CATALOG_OUTBOX_EVENT.EVENT_TYPE)
                .from(CATALOG_OUTBOX_EVENT)
                .where(CATALOG_OUTBOX_EVENT.AGGREGATE_ID.eq(AVAILABLE_EDITION_ID))
                .orderBy(CATALOG_OUTBOX_EVENT.AGGREGATE_VERSION)
                .fetch(CATALOG_OUTBOX_EVENT.EVENT_TYPE),
        )
    }

    @Test
    fun `update commands reject malformed versions missing targets and no-op activation`() {
        val workBody = """
            {
              "title": "Atlas of Quiet Rooms",
              "summary": "A hidden archive",
              "description": "A hidden archive in depth",
              "genre": "Fiction",
              "authors": [{
                "contributorId": "$PRIMARY_AUTHOR_ID",
                "name": "Maya Author",
                "bio": "Primary biography"
              }],
              "reason": "Verify rejected update command behavior"
            }
        """.trimIndent()

        mockMvc.perform(
            put("/api/v1/catalog/works/$FICTION_WORK_ID")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "0")
                .header("Idempotency-Key", "catalog-malformed-etag")
                .content(workBody)
                .with(commandScope()),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            put("/api/v1/catalog/works/49000000-0000-0000-0000-000000000001")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "catalog-missing-work01")
                .content(workBody)
                .with(commandScope()),
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/v1/catalog/editions/$AVAILABLE_EDITION_ID/activation")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "catalog-noop-active01")
                .content("""{"isActive":true,"reason":"Verify no-op activation rejection"}""")
                .with(commandScope()),
        ).andExpect(status().isConflict)

        assertEquals(0, dsl.fetchCount(CATALOG_OUTBOX_EVENT))
        assertEquals(0, dsl.fetchCount(CATALOG_AUDIT_ENTRY))
        assertEquals(0, dsl.fetchCount(CATALOG_COMMAND_IDEMPOTENCY))
    }

    @Test
    fun `outbox claims preserve aggregate order and recover expired retries`() {
        val workId = UUID.fromString("4a000000-0000-0000-0000-000000000001")
        val contributorId = UUID.fromString("4a000000-0000-0000-0000-000000000002")
        val owner = "a".repeat(64)
        commandService.createWork(
            CreateWorkCommand(
                workId = workId,
                title = "Ordered Event Work",
                summary = "Outbox ordering",
                description = "Validate strict aggregate event ordering",
                genre = "Systems",
                authors = listOf(CatalogAuthorInput(contributorId, "Event Author", null)),
                reason = "Create ordered outbox integration fixture",
                idempotencyKey = "catalog-outbox-create01",
                ownerFingerprint = owner,
            ),
        )
        commandService.updateWork(
            UpdateWorkCommand(
                workId = workId,
                expectedVersion = 0,
                title = "Ordered Event Work Revised",
                summary = "Outbox ordering revised",
                description = "Validate strict aggregate event ordering after update",
                genre = "Systems",
                authors = listOf(CatalogAuthorInput(contributorId, "Event Author", null)),
                reason = "Advance ordered outbox integration fixture",
                idempotencyKey = "catalog-outbox-update01",
                ownerFingerprint = owner,
            ),
        )

        val claimAt = Instant.now().plusSeconds(1)
        val first = outboxStore.claimBatch("catalog-test", claimAt, claimAt.plusSeconds(30), 10)
        assertEquals(listOf(0L), first.filter { it.aggregateId == workId }.map { it.aggregateVersion })
        val firstEvent = first.single { it.aggregateId == workId }
        assertEquals(
            true,
            outboxStore.markPublished(
                "catalog-test",
                firstEvent,
                BrokerAcknowledgement("mundia.catalog.events.v1", 0, 10),
                claimAt.plusSeconds(1),
            ),
        )

        val second = outboxStore.claimBatch("catalog-test", claimAt.plusSeconds(2), claimAt.plusSeconds(32), 10)
            .single { it.aggregateId == workId }
        assertEquals(1L, second.aggregateVersion)
        assertEquals(
            CatalogOutboxFailureDisposition.RETRY_SCHEDULED,
            outboxStore.recordFailure(
                "catalog-test",
                second,
                CatalogOutboxFailureCode.BROKER_TIMEOUT,
                claimAt.plusSeconds(3),
                claimAt.plusSeconds(10),
                maximumAttempts = 3,
                blockImmediately = false,
            ),
        )
        assertEquals(
            0,
            outboxStore.claimBatch("catalog-test", claimAt.plusSeconds(9), claimAt.plusSeconds(39), 10)
                .count { it.aggregateId == workId },
        )
        val retry = outboxStore.claimBatch(
            "catalog-test",
            claimAt.plusSeconds(10),
            claimAt.plusSeconds(40),
            10,
        ).single { it.aggregateId == workId }
        assertEquals(2, retry.deliveryAttempt)
        assertEquals(
            CatalogOutboxFailureDisposition.BLOCKED,
            outboxStore.recordFailure(
                "catalog-test",
                retry,
                CatalogOutboxFailureCode.CONTRACT_INVALID,
                claimAt.plusSeconds(11),
                claimAt.plusSeconds(20),
                maximumAttempts = 3,
                blockImmediately = true,
            ),
        )
        assertEquals(1, outboxStore.statistics(claimAt.plusSeconds(12)).blocked)
    }

    private fun insertContributor(id: UUID, name: String, biography: String?) {
        dsl.insertInto(CATALOG_CONTRIBUTOR)
            .set(CATALOG_CONTRIBUTOR.CONTRIBUTOR_ID, id)
            .set(CATALOG_CONTRIBUTOR.NAME, name)
            .set(CATALOG_CONTRIBUTOR.BIOGRAPHY, biography)
            .set(CATALOG_CONTRIBUTOR.CREATED_AT, NOW)
            .set(CATALOG_CONTRIBUTOR.UPDATED_AT, NOW)
            .execute()
    }

    private fun insertWork(id: UUID, title: String, summary: String, genre: String, rating: String) {
        dsl.insertInto(CATALOG_WORK)
            .set(CATALOG_WORK.WORK_ID, id)
            .set(CATALOG_WORK.TITLE, title)
            .set(CATALOG_WORK.SUMMARY, summary)
            .set(CATALOG_WORK.DESCRIPTION, "$summary in depth")
            .set(CATALOG_WORK.GENRE, genre)
            .set(CATALOG_WORK.RATING, BigDecimal(rating))
            .set(CATALOG_WORK.RATING_COUNT, 12)
            .set(CATALOG_WORK.CREATED_AT, NOW)
            .set(CATALOG_WORK.UPDATED_AT, NOW)
            .execute()
    }

    private fun linkAuthor(workId: UUID, contributorId: UUID, order: Int) {
        dsl.insertInto(CATALOG_WORK_CONTRIBUTOR)
            .set(CATALOG_WORK_CONTRIBUTOR.WORK_ID, workId)
            .set(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTOR_ID, contributorId)
            .set(CATALOG_WORK_CONTRIBUTOR.CONTRIBUTION_ROLE, "AUTHOR")
            .set(CATALOG_WORK_CONTRIBUTOR.DISPLAY_ORDER, order)
            .execute()
    }

    private fun insertEdition(
        id: UUID,
        workId: UUID,
        title: String,
        isbn: String,
        publicationYear: Int,
        active: Boolean,
        coverUrl: String?,
    ) {
        dsl.insertInto(CATALOG_EDITION)
            .set(CATALOG_EDITION.EDITION_ID, id)
            .set(CATALOG_EDITION.WORK_ID, workId)
            .set(CATALOG_EDITION.TITLE, title)
            .set(CATALOG_EDITION.ISBN, isbn)
            .set(CATALOG_EDITION.PUBLISHER, "Mundiapolis Press")
            .set(CATALOG_EDITION.PUBLICATION_YEAR, publicationYear)
            .set(CATALOG_EDITION.LANGUAGE, "English")
            .set(CATALOG_EDITION.PAGE_COUNT, 240)
            .set(CATALOG_EDITION.COVER_URL, coverUrl)
            .set(CATALOG_EDITION.COVER_COLOR, "#123ABC")
            .set(CATALOG_EDITION.VIDEO_URL, null as String?)
            .set(CATALOG_EDITION.IS_ACTIVE, active)
            .set(CATALOG_EDITION.CREATED_AT, NOW)
            .set(CATALOG_EDITION.UPDATED_AT, NOW)
            .execute()
    }

    private fun insertAvailability(editionId: UUID, total: Int, available: Int, version: Long) {
        dsl.insertInto(CATALOG_EDITION_AVAILABILITY_PROJECTION)
            .set(CATALOG_EDITION_AVAILABILITY_PROJECTION.EDITION_ID, editionId)
            .set(CATALOG_EDITION_AVAILABILITY_PROJECTION.TOTAL_COPIES, total)
            .set(CATALOG_EDITION_AVAILABILITY_PROJECTION.AVAILABLE_COPIES, available)
            .set(CATALOG_EDITION_AVAILABILITY_PROJECTION.SOURCE_VERSION, version)
            .set(CATALOG_EDITION_AVAILABILITY_PROJECTION.SOURCE_OCCURRED_AT, NOW.minusMinutes(1))
            .set(CATALOG_EDITION_AVAILABILITY_PROJECTION.UPDATED_AT, NOW)
            .execute()
    }

    private fun insertReview(
        reviewId: UUID,
        workId: UUID,
        memberId: UUID,
        rating: Int,
        content: String,
        status: String,
        createdAt: OffsetDateTime,
    ) {
        dsl.insertInto(CATALOG_REVIEW)
            .set(CATALOG_REVIEW.REVIEW_ID, reviewId)
            .set(CATALOG_REVIEW.WORK_ID, workId)
            .set(CATALOG_REVIEW.MEMBER_ID, memberId)
            .set(CATALOG_REVIEW.RATING, rating.toShort())
            .set(CATALOG_REVIEW.CONTENT, content)
            .set(CATALOG_REVIEW.MODERATION_STATUS, status)
            .set(CATALOG_REVIEW.CREATED_AT, createdAt)
            .set(CATALOG_REVIEW.UPDATED_AT, createdAt)
            .execute()
    }

    private fun circulationCopyEvent(
        copyId: UUID,
        version: Long,
        status: ProjectedCopyStatus,
    ): CirculationCopyEvent = CirculationCopyEvent(
        eventId = UUID.randomUUID(),
        eventType = if (version == 0L) {
            "circulation.copy.registered"
        } else {
            "circulation.copy.status-changed"
        },
        eventVersion = 1,
        copyId = copyId,
        editionId = AVAILABLE_EDITION_ID,
        aggregateVersion = version,
        status = status,
        occurredAt = NOW.toInstant(),
        payloadSha256 = UUID.randomUUID().toString().replace("-", "").repeat(2),
    )

    private fun assertAvailability(total: Int, available: Int) {
        val projection = dsl.selectFrom(CATALOG_EDITION_AVAILABILITY_PROJECTION)
            .where(CATALOG_EDITION_AVAILABILITY_PROJECTION.EDITION_ID.eq(AVAILABLE_EDITION_ID))
            .fetchSingle()
        assertEquals(total, projection.totalCopies)
        assertEquals(available, projection.availableCopies)
    }

    private fun scope(authority: String) = jwt().authorities(SimpleGrantedAuthority(authority))

    private fun commandScope() = jwt()
        .jwt {
            it.issuer("https://issuer.example.test")
                .subject("catalog-admin")
                .claim("azp", "catalog-bff")
        }
        .authorities(SimpleGrantedAuthority(MANAGE_SCOPE))

    private fun createWorkBody(workId: UUID, contributorId: UUID, title: String): String = """
        {
          "workId": "$workId",
          "title": "$title",
          "summary": "A reviewed summary",
          "description": "A reviewed catalog description",
          "genre": "History",
          "authors": [
            {
              "contributorId": "$contributorId",
              "name": "Command Author",
              "bio": "A bounded biography"
            }
          ],
          "reason": "Initial reviewed catalog import"
        }
    """.trimIndent()

    private companion object {
        val PRIMARY_AUTHOR_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val SECONDARY_AUTHOR_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val FICTION_WORK_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val SCIENCE_WORK_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val AVAILABLE_EDITION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val UNAVAILABLE_EDITION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000002")
        val INACTIVE_EDITION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000003")
        val PUBLISHED_REVIEW_ID: UUID = UUID.fromString("60000000-0000-0000-0000-000000000001")
        val OLDER_REVIEW_ID: UUID = UUID.fromString("60000000-0000-0000-0000-000000000002")
        val HIDDEN_REVIEW_ID: UUID = UUID.fromString("60000000-0000-0000-0000-000000000003")
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)

        const val READ_SCOPE = "SCOPE_catalog.read"
        const val SEARCH_SCOPE = "SCOPE_catalog.search"
        const val MANAGE_SCOPE = "SCOPE_catalog.manage"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("integration-test-only")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("app.security.jwt.issuer") { "https://issuer.example.test" }
            registry.add("app.security.jwt.jwk-set-uri") {
                "https://issuer.example.test/.well-known/jwks.json"
            }
            registry.add("app.security.jwt.audience") { "catalog-api" }
        }
    }
}
