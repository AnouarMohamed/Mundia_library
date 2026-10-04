package com.mundiapolis.library.digitalcontent

import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_ASSET
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DigitalContentIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun seedAssets() {
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
        val assetId = UUID.randomUUID()
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
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 10, 4, 12, 0, 0, 0, ZoneOffset.UTC)
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
