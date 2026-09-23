package com.mundiapolis.library.membership

import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_IDENTITY_EVIDENCE
import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_MEMBER
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
class MembershipServiceIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun seedMembership() {
        dsl.deleteFrom(MEMBERSHIP_IDENTITY_EVIDENCE).execute()
        dsl.deleteFrom(MEMBERSHIP_MEMBER).execute()
        insertMember(APPROVED_MEMBER_ID, "APPROVED", activeLoans = 1, maximumLoans = 5)
        insertMember(LIMITED_MEMBER_ID, "APPROVED", activeLoans = 2, maximumLoans = 2)
        insertMember(PENDING_MEMBER_ID, "PENDING", activeLoans = 0, maximumLoans = 5)

        dsl.insertInto(MEMBERSHIP_IDENTITY_EVIDENCE)
            .set(MEMBERSHIP_IDENTITY_EVIDENCE.EVIDENCE_ID, EVIDENCE_ID)
            .set(MEMBERSHIP_IDENTITY_EVIDENCE.MEMBER_ID, APPROVED_MEMBER_ID)
            .set(MEMBERSHIP_IDENTITY_EVIDENCE.OBJECT_KEY, "private/members/$APPROVED_MEMBER_ID/id-card")
            .set(MEMBERSHIP_IDENTITY_EVIDENCE.MIME_TYPE, "application/pdf")
            .set(MEMBERSHIP_IDENTITY_EVIDENCE.FILE_SIZE, 4096)
            .set(MEMBERSHIP_IDENTITY_EVIDENCE.CHECKSUM_SHA256, "a".repeat(64))
            .set(MEMBERSHIP_IDENTITY_EVIDENCE.UPLOADED_AT, NOW)
            .execute()
    }

    @Test
    fun `self-service profile read requires a matching membership claim`() {
        mockMvc.perform(
            get("/api/v1/members/$APPROVED_MEMBER_ID/profile")
                .with(memberJwt(APPROVED_MEMBER_ID, PROFILE_SELF_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memberId").value(APPROVED_MEMBER_ID.toString()))
            .andExpect(jsonPath("$.email").value("member-${APPROVED_MEMBER_ID.toString().take(8)}@example.test"))
            .andExpect(jsonPath("$.status").value("APPROVED"))

        mockMvc.perform(
            get("/api/v1/members/$APPROVED_MEMBER_ID/profile")
                .with(memberJwt(LIMITED_MEMBER_ID, PROFILE_SELF_SCOPE)),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `delegated profile read can access another member but unknown members return not found`() {
        mockMvc.perform(
            get("/api/v1/members/$APPROVED_MEMBER_ID/profile")
                .with(jwt().authorities(SimpleGrantedAuthority(PROFILE_ANY_SCOPE))),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/v1/members/${UUID.randomUUID()}/profile")
                .with(jwt().authorities(SimpleGrantedAuthority(PROFILE_ANY_SCOPE))),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `eligibility is derived fail closed from authoritative membership state`() {
        mockMvc.perform(
            get("/api/v1/members/$APPROVED_MEMBER_ID/eligibility")
                .with(memberJwt(APPROVED_MEMBER_ID, ELIGIBILITY_SELF_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eligible").value(true))
            .andExpect(jsonPath("$.reason").doesNotExist())

        mockMvc.perform(
            get("/api/v1/members/$LIMITED_MEMBER_ID/eligibility")
                .with(jwt().authorities(SimpleGrantedAuthority(ELIGIBILITY_ANY_SCOPE))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eligible").value(false))
            .andExpect(jsonPath("$.reason").value("Active loan limit reached"))

        mockMvc.perform(
            get("/api/v1/members/$PENDING_MEMBER_ID/eligibility")
                .with(jwt().authorities(SimpleGrantedAuthority(ELIGIBILITY_ANY_SCOPE))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eligible").value(false))
            .andExpect(jsonPath("$.reason").value("Account is not approved"))
    }

    @Test
    fun `identity evidence read never exposes the private object key or a durable URL`() {
        mockMvc.perform(
            get("/api/v1/members/$APPROVED_MEMBER_ID/identity-evidence")
                .with(jwt().authorities(SimpleGrantedAuthority(EVIDENCE_SCOPE))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.evidenceId").value(EVIDENCE_ID.toString()))
            .andExpect(jsonPath("$.checksumSha256").value("a".repeat(64)))
            .andExpect(jsonPath("$.objectKey").doesNotExist())
            .andExpect(jsonPath("$.signedReadUrl").doesNotExist())
    }

    @Test
    fun `protected reads reject missing authentication and missing scopes`() {
        mockMvc.perform(get("/api/v1/members/$APPROVED_MEMBER_ID/profile"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/api/v1/members/$APPROVED_MEMBER_ID/profile").with(jwt()),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `health probes remain public`() {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/openapi/membership-v1.json"))
            .andExpect(status().isOk)
    }

    private fun insertMember(
        memberId: UUID,
        status: String,
        activeLoans: Int,
        maximumLoans: Int,
    ) {
        dsl.insertInto(MEMBERSHIP_MEMBER)
            .set(MEMBERSHIP_MEMBER.MEMBER_ID, memberId)
            .set(
                MEMBERSHIP_MEMBER.EMAIL,
                "member-${memberId.toString().take(8)}@example.test",
            )
            .set(MEMBERSHIP_MEMBER.FULL_NAME, "Integration Member")
            .set(MEMBERSHIP_MEMBER.UNIVERSITY_ID, memberId.hashCode().and(Int.MAX_VALUE) + 1)
            .set(MEMBERSHIP_MEMBER.ACCOUNT_STATUS, status)
            .set(MEMBERSHIP_MEMBER.MEMBERSHIP_ROLE, "USER")
            .set(MEMBERSHIP_MEMBER.MAX_ACTIVE_LOANS, maximumLoans)
            .set(MEMBERSHIP_MEMBER.CURRENT_ACTIVE_LOANS, activeLoans)
            .set(MEMBERSHIP_MEMBER.HAS_UNPAID_OVERDUE_FINES, false)
            .set(MEMBERSHIP_MEMBER.CREATED_AT, NOW)
            .set(MEMBERSHIP_MEMBER.UPDATED_AT, NOW)
            .execute()
    }

    private fun memberJwt(memberId: UUID, authority: String) = jwt()
        .jwt { it.claim("membership_id", memberId.toString()) }
        .authorities(SimpleGrantedAuthority(authority))

    private companion object {
        val APPROVED_MEMBER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val LIMITED_MEMBER_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val PENDING_MEMBER_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000003")
        val EVIDENCE_ID: UUID = UUID.fromString("40000000-0000-0000-0000-000000000004")
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 9, 22, 12, 0, 0, 0, ZoneOffset.UTC)

        const val PROFILE_SELF_SCOPE = "SCOPE_membership.profile.read"
        const val PROFILE_ANY_SCOPE = "SCOPE_membership.profile.read.any"
        const val ELIGIBILITY_SELF_SCOPE = "SCOPE_membership.eligibility.read"
        const val ELIGIBILITY_ANY_SCOPE = "SCOPE_membership.eligibility.read.any"
        const val EVIDENCE_SCOPE = "SCOPE_membership.identity-evidence.read"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("membership")
            .withUsername("membership")
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
            registry.add("app.security.jwt.audience") { "membership-api" }
        }
    }
}
