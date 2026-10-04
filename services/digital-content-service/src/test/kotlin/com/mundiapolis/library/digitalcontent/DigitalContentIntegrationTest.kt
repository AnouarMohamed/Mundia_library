package com.mundiapolis.library.digitalcontent

import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_ASSET
import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_DOWNLOAD_AUTHORIZATION_AUDIT
import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_INGESTION
import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_SCAN_RECEIPT
import com.mundiapolis.library.digitalcontent.service.DownloadUrlSigner
import com.mundiapolis.library.digitalcontent.service.QuarantineUploadAuthorizer
import com.mundiapolis.library.digitalcontent.service.SignedUpload
import com.mundiapolis.library.digitalcontent.service.MalwareScanEvent
import com.mundiapolis.library.digitalcontent.service.MalwareScanResult
import com.mundiapolis.library.digitalcontent.service.MalwareScanService
import com.mundiapolis.library.digitalcontent.service.SignedDownload
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.OffsetDateTime
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(DigitalContentIntegrationTest.DownloadSignerTestConfiguration::class)
class DigitalContentIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var malwareScanService: MalwareScanService

    @BeforeEach
    fun seedAssets() {
        dsl.deleteFrom(DIGITAL_CONTENT_DOWNLOAD_AUTHORIZATION_AUDIT).execute()
        dsl.deleteFrom(DIGITAL_CONTENT_SCAN_RECEIPT).execute()
        dsl.deleteFrom(DIGITAL_CONTENT_INGESTION).execute()
        dsl.deleteFrom(DIGITAL_CONTENT_ASSET).execute()
        insertAsset(
            editionId = AVAILABLE_EDITION_ID,
            format = "PDF",
            mediaType = "application/pdf",
            rightsStatus = "VERIFIED",
            rightsExpiresAt = NOW.plusDays(30),
            territoryScope = "GLOBAL",
            scanStatus = "CLEAN",
            publicationStatus = "PUBLISHED",
        )
        insertAsset(
            editionId = BLOCKED_EDITION_ID,
            format = "EPUB",
            mediaType = "application/epub+zip",
            rightsStatus = "VERIFIED",
            rightsExpiresAt = NOW.plusDays(30),
            territoryScope = "GLOBAL",
            scanStatus = "PENDING",
            publicationStatus = "PUBLISHED",
        )
        insertAsset(
            editionId = RESTRICTED_EDITION_ID,
            format = "PDF",
            mediaType = "application/pdf",
            rightsStatus = "VERIFIED",
            rightsExpiresAt = NOW.plusDays(30),
            territoryScope = "RESTRICTED",
            scanStatus = "CLEAN",
            publicationStatus = "PUBLISHED",
        )
        insertAsset(
            editionId = EXPIRED_EDITION_ID,
            format = "PDF",
            mediaType = "application/pdf",
            rightsStatus = "VERIFIED",
            rightsExpiresAt = NOW.minusHours(1),
            territoryScope = "GLOBAL",
            scanStatus = "CLEAN",
            publicationStatus = "PUBLISHED",
        )
    }

    @Test
    fun `availability exposes only globally authorized clean published assets`() {
        mockMvc.perform(
            get("/api/v1/digital-content/editions/$AVAILABLE_EDITION_ID/availability")
                .with(readScope()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=30, must-revalidate, private"))
            .andExpect(jsonPath("$.editionId").value(AVAILABLE_EDITION_ID.toString()))
            .andExpect(jsonPath("$.downloadable").value(true))
            .andExpect(jsonPath("$.formats.length()").value(1))
            .andExpect(jsonPath("$.formats[0].format").value("PDF"))
            .andExpect(jsonPath("$.formats[0].licenseExpression").value("CC-BY-4.0"))
            .andExpect(jsonPath("$.formats[0].objectKey").doesNotExist())
            .andExpect(jsonPath("$.formats[0].sourceUri").doesNotExist())

        mockMvc.perform(
            get("/api/v1/digital-content/editions/$BLOCKED_EDITION_ID/availability")
                .with(readScope()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.downloadable").value(false))
            .andExpect(jsonPath("$.formats.length()").value(0))

        listOf(RESTRICTED_EDITION_ID, EXPIRED_EDITION_ID).forEach { editionId ->
            mockMvc.perform(
                get("/api/v1/digital-content/editions/$editionId/availability")
                    .with(readScope()),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.downloadable").value(false))
                .andExpect(jsonPath("$.formats.length()").value(0))
        }
    }

    @Test
    fun `availability requires authentication and least privilege scope`() {
        val path = "/api/v1/digital-content/editions/$AVAILABLE_EDITION_ID/availability"
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
        mockMvc.perform(get(path).with(jwt())).andExpect(status().isForbidden)
    }

    @Test
    fun `authorization rechecks eligibility and records only a privacy safe audit`() {
        val path = "/api/v1/digital-content/assets/$AVAILABLE_ASSET_ID/authorizations"
        mockMvc.perform(post(path).with(downloadScope()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.assetId").value(AVAILABLE_ASSET_ID.toString()))
            .andExpect(jsonPath("$.downloadUrl").value("https://downloads.example.test/signed"))
            .andExpect(jsonPath("$.expiresAt").exists())

        val audit = dsl.selectFrom(DIGITAL_CONTENT_DOWNLOAD_AUTHORIZATION_AUDIT).fetchSingle()
        org.junit.jupiter.api.Assertions.assertEquals(AVAILABLE_ASSET_ID, audit.assetId)
        org.junit.jupiter.api.Assertions.assertTrue(
            requireNotNull(audit.actorFingerprint).matches(Regex("^[0-9a-f]{64}$")),
        )
        org.junit.jupiter.api.Assertions.assertFalse(audit.actorFingerprint.contains("member-test"))

        mockMvc.perform(
            post("/api/v1/digital-content/assets/$BLOCKED_ASSET_ID/authorizations")
                .with(downloadScope()),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("download_not_available"))
    }

    @Test
    fun `authorization requires authentication and its dedicated scope`() {
        val path = "/api/v1/digital-content/assets/$AVAILABLE_ASSET_ID/authorizations"
        mockMvc.perform(post(path)).andExpect(status().isUnauthorized)
        mockMvc.perform(post(path).with(readScope())).andExpect(status().isForbidden)
    }

    @Test
    fun `ingestion command is caller bound idempotent and issues a constrained grant`() {
        val path = "/api/v1/digital-content/ingestions/$INGESTION_ID"
        mockMvc.perform(
            put(path)
                .with(ingestionScope())
                .contentType("application/json")
                .content(INGESTION_BODY),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.ingestionId").value(INGESTION_ID.toString()))
            .andExpect(jsonPath("$.state").value("AWAITING_SCAN"))
            .andExpect(jsonPath("$.method").value("PUT"))
            .andExpect(jsonPath("$.requiredHeaders.x-amz-checksum-sha256").value("checksum"))
            .andExpect(jsonPath("$.replayed").value(false))

        mockMvc.perform(
            put(path)
                .with(ingestionScope())
                .contentType("application/json")
                .content(INGESTION_BODY),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.replayed").value(true))

        val record = dsl.selectFrom(DIGITAL_CONTENT_INGESTION).fetchSingle()
        org.junit.jupiter.api.Assertions.assertEquals("AWAITING_SCAN", record.state)
        org.junit.jupiter.api.Assertions.assertEquals(DIGEST, record.sha256)
        org.junit.jupiter.api.Assertions.assertTrue(
            requireNotNull(record.quarantineObjectKey).startsWith("quarantine/digital-content/aa/$INGESTION_ID/"),
        )
        org.junit.jupiter.api.Assertions.assertFalse(record.actorFingerprint.contains("ingestion-operator"))
    }

    @Test
    fun `ingestion rejects identifier reuse by a different caller and requires least privilege`() {
        val path = "/api/v1/digital-content/ingestions/$INGESTION_ID"
        mockMvc.perform(put(path).contentType("application/json").content(INGESTION_BODY))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(put(path).with(readScope()).contentType("application/json").content(INGESTION_BODY))
            .andExpect(status().isForbidden)

        mockMvc.perform(
            put(path).with(ingestionScope("operator-one")).contentType("application/json").content(INGESTION_BODY),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            put(path).with(ingestionScope("operator-two")).contentType("application/json").content(INGESTION_BODY),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ingestion_id_conflict"))
    }

    @Test
    fun `scan receipts are idempotent and any later adverse result fails closed`() {
        mockMvc.perform(
            put("/api/v1/digital-content/ingestions/$INGESTION_ID")
                .with(ingestionScope())
                .contentType("application/json")
                .content(INGESTION_BODY),
        ).andExpect(status().isCreated)
        val key = dsl.select(DIGITAL_CONTENT_INGESTION.QUARANTINE_OBJECT_KEY)
            .from(DIGITAL_CONTENT_INGESTION)
            .fetchSingle(DIGITAL_CONTENT_INGESTION.QUARANTINE_OBJECT_KEY)
        val event = MalwareScanEvent(
            eventId = SCAN_EVENT_ID,
            objectKey = requireNotNull(key),
            objectVersionId = "version-1",
            objectEtag = "etag-1",
            result = MalwareScanResult.NO_THREATS_FOUND,
            eventAt = Instant.now().minusSeconds(1),
            payloadDigest = "b".repeat(64),
        )
        org.junit.jupiter.api.Assertions.assertEquals("CLEAN", malwareScanService.apply(event).state)
        org.junit.jupiter.api.Assertions.assertTrue(malwareScanService.apply(event).replayed)

        val adverse = event.copy(
            eventId = UUID.fromString("14000000-0000-0000-0000-000000000002"),
            result = MalwareScanResult.THREATS_FOUND,
            payloadDigest = "c".repeat(64),
        )
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED", malwareScanService.apply(adverse).state)
        org.junit.jupiter.api.Assertions.assertEquals(
            "REJECTED",
            dsl.select(DIGITAL_CONTENT_INGESTION.STATE)
                .from(DIGITAL_CONTENT_INGESTION)
                .fetchSingle(DIGITAL_CONTENT_INGESTION.STATE),
        )
        org.junit.jupiter.api.Assertions.assertEquals(2, dsl.fetchCount(DIGITAL_CONTENT_SCAN_RECEIPT))
    }

    private fun insertAsset(
        editionId: UUID,
        format: String,
        mediaType: String,
        rightsStatus: String,
        rightsExpiresAt: OffsetDateTime?,
        territoryScope: String,
        scanStatus: String,
        publicationStatus: String,
    ) {
        val assetId = when (editionId) {
            AVAILABLE_EDITION_ID -> AVAILABLE_ASSET_ID
            BLOCKED_EDITION_ID -> BLOCKED_ASSET_ID
            else -> UUID.randomUUID()
        }
        val extension = format.lowercase()
        dsl.insertInto(DIGITAL_CONTENT_ASSET)
            .set(DIGITAL_CONTENT_ASSET.ASSET_ID, assetId)
            .set(DIGITAL_CONTENT_ASSET.EDITION_ID, editionId)
            .set(DIGITAL_CONTENT_ASSET.FORMAT, format)
            .set(DIGITAL_CONTENT_ASSET.MEDIA_TYPE, mediaType)
            .set(DIGITAL_CONTENT_ASSET.SIZE_BYTES, 4096L)
            .set(DIGITAL_CONTENT_ASSET.SHA256, DIGEST)
            .set(
                DIGITAL_CONTENT_ASSET.OBJECT_KEY,
                "digital-content/ab/$assetId/$DIGEST.$extension",
            )
            .set(DIGITAL_CONTENT_ASSET.SOURCE_PROVIDER, "OpenStax")
            .set(DIGITAL_CONTENT_ASSET.SOURCE_URI, "https://openstax.example.test/books/source")
            .set(DIGITAL_CONTENT_ASSET.LICENSE_EXPRESSION, "CC-BY-4.0")
            .set(DIGITAL_CONTENT_ASSET.ATTRIBUTION, "Example Engineering Text, OpenStax")
            .set(DIGITAL_CONTENT_ASSET.RIGHTS_STATUS, rightsStatus)
            .set(DIGITAL_CONTENT_ASSET.RIGHTS_VERIFIED_AT, NOW.minusDays(1))
            .set(DIGITAL_CONTENT_ASSET.RIGHTS_EXPIRES_AT, rightsExpiresAt)
            .set(DIGITAL_CONTENT_ASSET.TERRITORY_SCOPE, territoryScope)
            .set(DIGITAL_CONTENT_ASSET.MALWARE_SCAN_STATUS, scanStatus)
            .set(DIGITAL_CONTENT_ASSET.PUBLICATION_STATUS, publicationStatus)
            .execute()
    }

    private fun readScope() = jwt().authorities(
        SimpleGrantedAuthority("SCOPE_digital-content.availability.read"),
    )

    private fun downloadScope() = jwt()
        .jwt { token ->
            token.issuer("https://identity.example.test")
            token.subject("member-test")
        }
        .authorities(SimpleGrantedAuthority("SCOPE_digital-content.download.authorize"))

    private fun ingestionScope(subject: String = "ingestion-operator") = jwt()
        .jwt { token ->
            token.issuer("https://identity.example.test")
            token.subject(subject)
        }
        .authorities(SimpleGrantedAuthority("SCOPE_digital-content.ingestion.create"))

    @TestConfiguration(proxyBeanMethods = false)
    class DownloadSignerTestConfiguration {
        @Bean
        @Primary
        fun testDownloadUrlSigner() = DownloadUrlSigner { _, issuedAt ->
            SignedDownload("https://downloads.example.test/signed", issuedAt.plusSeconds(60))
        }

        @Bean
        @Primary
        fun testQuarantineUploadAuthorizer() = QuarantineUploadAuthorizer { _, issuedAt ->
            SignedUpload(
                "https://quarantine.example.test/upload",
                mapOf("x-amz-checksum-sha256" to "checksum"),
                issuedAt.plusSeconds(300),
            )
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("app.security.jwt.issuer") { "https://identity.example.test" }
            registry.add("app.security.jwt.jwk-set-uri") {
                "https://identity.example.test/.well-known/jwks.json"
            }
            registry.add("app.security.jwt.audience") { "digital-content-api" }
        }

        val AVAILABLE_EDITION_ID: UUID = UUID.fromString("11000000-0000-0000-0000-000000000001")
        val BLOCKED_EDITION_ID: UUID = UUID.fromString("11000000-0000-0000-0000-000000000002")
        val RESTRICTED_EDITION_ID: UUID = UUID.fromString("11000000-0000-0000-0000-000000000003")
        val EXPIRED_EDITION_ID: UUID = UUID.fromString("11000000-0000-0000-0000-000000000004")
        val AVAILABLE_ASSET_ID: UUID = UUID.fromString("12000000-0000-0000-0000-000000000001")
        val BLOCKED_ASSET_ID: UUID = UUID.fromString("12000000-0000-0000-0000-000000000002")
        val INGESTION_ID: UUID = UUID.fromString("13000000-0000-0000-0000-000000000001")
        val SCAN_EVENT_ID: UUID = UUID.fromString("14000000-0000-0000-0000-000000000001")
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 10, 4, 12, 0, 0, 0, ZoneOffset.UTC)
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val INGESTION_BODY = """
            {
              "editionId": "$AVAILABLE_EDITION_ID",
              "format": "PDF",
              "sizeBytes": 4096,
              "sha256": "$DIGEST",
              "sourceProvider": "OpenStax",
              "sourceUri": "https://openstax.org/details/books/example",
              "licenseExpression": "CC-BY-4.0",
              "attribution": "Example Engineering Text, OpenStax",
              "rightsExpiresAt": null
            }
        """.trimIndent()
    }
}
