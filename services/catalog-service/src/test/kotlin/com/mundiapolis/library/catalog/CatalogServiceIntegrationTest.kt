package com.mundiapolis.library.catalog

import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_CONTRIBUTOR
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_AUDIT_ENTRY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_COMMAND_IDEMPOTENCY
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_EDITION_AVAILABILITY_PROJECTION
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_OUTBOX_EVENT
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_REVIEW
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK
import com.mundiapolis.library.catalog.adapter.outbound.persistence.jooq.generated.Tables.CATALOG_WORK_CONTRIBUTOR
import com.mundiapolis.library.catalog.dto.CatalogAuthorInput
import com.mundiapolis.library.catalog.dto.CreateWorkCommand
import com.mundiapolis.library.catalog.service.CatalogCommandService
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.OffsetDateTime
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

    @BeforeEach
    fun seedCatalog() {
        dsl.deleteFrom(CATALOG_OUTBOX_EVENT).execute()
        dsl.deleteFrom(CATALOG_AUDIT_ENTRY).execute()
        dsl.deleteFrom(CATALOG_COMMAND_IDEMPOTENCY).execute()
        dsl.deleteFrom(CATALOG_REVIEW).execute()
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
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 9, 24, 12, 0, 0, 0, ZoneOffset.UTC)

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
