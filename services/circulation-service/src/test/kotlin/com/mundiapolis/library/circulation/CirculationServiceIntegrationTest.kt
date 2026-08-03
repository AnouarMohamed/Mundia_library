package com.mundiapolis.library.circulation

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_COPY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_MEMBER_ELIGIBILITY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.CommandExecution
import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.LoanStateConflictException
import com.mundiapolis.library.circulation.application.model.NoAvailableCopyException
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.CancelLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.CancelLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationStatusQuery
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RejectLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RejectLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanUseCase
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "app.security.jwt.issuer=https://issuer.example.test",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.audience=circulation-api",
        "app.circulation.default-loan-period=P14D",
        "app.circulation.idempotency-retention=P1D",
        "spring.datasource.hikari.maximum-pool-size=24",
        "spring.datasource.hikari.connection-timeout=10000",
    ],
)
class CirculationServiceIntegrationTest {
    @Autowired
    private lateinit var getCirculationStatus: GetCirculationStatusQuery

    @Autowired
    private lateinit var requestLoan: RequestLoanUseCase

    @Autowired
    private lateinit var approveLoan: ApproveLoanUseCase

    @Autowired
    private lateinit var rejectLoan: RejectLoanUseCase

    @Autowired
    private lateinit var cancelLoan: CancelLoanUseCase

    @Autowired
    private lateinit var returnLoan: ReturnLoanUseCase

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun cleanCommandData() {
        dsl.execute("ALTER TABLE circulation_fine_ledger_entry DISABLE TRIGGER USER")
        dsl.execute("ALTER TABLE circulation_inventory_audit_entry DISABLE TRIGGER USER")
        dsl.execute("ALTER TABLE circulation_consumer_inbox DISABLE TRIGGER USER")
        dsl.execute("ALTER TABLE circulation_member_eligibility DISABLE TRIGGER USER")
        try {
            dsl.execute(
                """
                TRUNCATE TABLE
                    circulation_fine_ledger_entry,
                    circulation_fine,
                    circulation_inventory_audit_entry,
                    circulation_consumer_inbox,
                    circulation_member_eligibility,
                    outbox_event,
                    circulation_idempotency,
                    circulation_inventory_idempotency,
                    circulation_loan,
                    circulation_copy
                """.trimIndent(),
            )
        } finally {
            dsl.execute("ALTER TABLE circulation_fine_ledger_entry ENABLE TRIGGER USER")
            dsl.execute("ALTER TABLE circulation_inventory_audit_entry ENABLE TRIGGER USER")
            dsl.execute("ALTER TABLE circulation_consumer_inbox ENABLE TRIGGER USER")
            dsl.execute("ALTER TABLE circulation_member_eligibility ENABLE TRIGGER USER")
        }
    }

    @Test
    fun `flyway schema and jooq adapter start together`() {
        val status = getCirculationStatus.getStatus()

        assertThat(status.service).isEqualTo("circulation-service")
        assertThat(status.activeLoans).isZero()
    }

    @Test
    fun `health and versioned contract are public but service data requires authentication and scope`() {
        mockMvc.get("/actuator/health/liveness")
            .andExpect {
                status { isOk() }
            }

        mockMvc.get("/openapi/circulation-v1.json")
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                jsonPath("$.openapi") { value("3.1.0") }
                jsonPath("$.info.version") { value("1.0.0") }
            }

        mockMvc.get("/actuator/prometheus")
            .andExpect {
                status { isUnauthorized() }
            }

        mockMvc.get("/actuator/prometheus") {
            with(jwt().authorities(SimpleGrantedAuthority("SCOPE_circulation.read")))
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.get("/actuator/prometheus") {
            with(jwt().authorities(SimpleGrantedAuthority("SCOPE_circulation.operations.read")))
        }.andExpect {
            status { isOk() }
        }

        mockMvc.get("/api/v1/circulation/status")
            .andExpect {
                status { isUnauthorized() }
            }

        mockMvc.get("/api/v1/circulation/status") {
            with(jwt().authorities(SimpleGrantedAuthority("SCOPE_circulation.read")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.service") { value("circulation-service") }
            jsonPath("$.activeLoans") { value(0) }
        }
    }

    @Test
    fun `request endpoint enforces bearer scope and idempotency contract`() {
        val memberId = UUID.randomUUID()
        val editionId = UUID.randomUUID()
        val requestBody = requestJson(memberId, editionId)
        val key = "request-http-${UUID.randomUUID()}"
        seedEligible(MemberId(memberId))

        mockMvc.post(LOANS_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("reader-user", "SCOPE_circulation.read", memberId))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("request-user", REQUEST_SCOPE, memberId))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andExpect {
            status { isBadRequest() }
        }

        val unknownLoanId = UUID.randomUUID()
        mockMvc.post("$LOANS_PATH/$unknownLoanId/approve") {
            header(IDEMPOTENCY_HEADER, "approve-http-${UUID.randomUUID()}")
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("$LOANS_PATH/$unknownLoanId/approve") {
            with(jwtFor("return-user", RETURN_SCOPE))
            header(IDEMPOTENCY_HEADER, "approve-http-${UUID.randomUUID()}")
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post("$LOANS_PATH/$unknownLoanId/approve") {
            with(jwtFor("approve-user", APPROVE_SCOPE))
        }.andExpect {
            status { isBadRequest() }
        }

        mockMvc.post("$LOANS_PATH/$unknownLoanId/return") {
            with(jwtFor("return-user", RETURN_SCOPE))
        }.andExpect {
            status { isBadRequest() }
        }

        val firstResponse = mockMvc.post(LOANS_PATH) {
            with(jwtFor("request-user", REQUEST_SCOPE, memberId))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
            jsonPath("$.memberId") { value(memberId.toString()) }
            jsonPath("$.editionId") { value(editionId.toString()) }
            jsonPath("$.status") { value("REQUESTED") }
            jsonPath("$.version") { value(0) }
        }.andReturn().response.contentAsString

        val replayResponse = mockMvc.post(LOANS_PATH) {
            with(jwtFor("request-user", REQUEST_SCOPE, memberId))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "true") }
        }.andReturn().response.contentAsString

        assertThat(replayResponse).isEqualTo(firstResponse)
        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isOne()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isOne()
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isOne()

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("request-user", REQUEST_SCOPE, memberId))
            contentType = MediaType.APPLICATION_JSON
            content = requestJson(memberId, UUID.randomUUID())
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("idempotency_key_conflict") }
        }

        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isOne()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isOne()
    }

    @Test
    fun `self service membership claim fails closed while staff scope permits on behalf`() {
        val targetMemberId = UUID.randomUUID()
        val requestBody = requestJson(targetMemberId, UUID.randomUUID())
        seedEligible(MemberId(targetMemberId))

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("missing-claim-user", REQUEST_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, "missing-claim-${UUID.randomUUID()}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("missing_membership_claim") }
        }

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("malformed-claim-user", REQUEST_SCOPE, "not-a-uuid"))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, "malformed-claim-${UUID.randomUUID()}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("invalid_authentication_claim") }
        }

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("other-member-user", REQUEST_SCOPE, UUID.randomUUID()))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, "cross-member-${UUID.randomUUID()}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("member_access_denied") }
        }

        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isZero()
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isZero()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isZero()

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("staff-on-behalf", ON_BEHALF_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
            header(IDEMPOTENCY_HEADER, "staff-on-behalf-${UUID.randomUUID()}")
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
            jsonPath("$.memberId") { value(targetMemberId.toString()) }
        }

        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isOne()
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isOne()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isOne()
    }

    @Test
    fun `rejection endpoint requires its exact staff scope and replays safely`() {
        val memberId = MemberId(UUID.randomUUID())
        val requested = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = EditionId(UUID.randomUUID()),
                idempotencyKey = IdempotencyKey.parse("request-http-reject-${UUID.randomUUID()}"),
                principal = eligibleSelfPrincipal(memberId, "http-rejected-member"),
            ),
        )
        val path = "$LOANS_PATH/${requested.result.loanId.value}/reject"
        val key = "reject-http-${UUID.randomUUID()}"

        mockMvc.post(path) {
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post(path) {
            with(jwtFor("wrong-scope-staff", APPROVE_SCOPE))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post(path) {
            with(jwtFor("rejecting-staff", REJECT_SCOPE))
        }.andExpect {
            status { isBadRequest() }
        }

        val firstResponse = mockMvc.post(path) {
            with(jwtFor("rejecting-staff", REJECT_SCOPE))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isOk() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
            jsonPath("$.status") { value("REJECTED") }
            jsonPath("$.copyId") { doesNotExist() }
            jsonPath("$.version") { value(1) }
        }.andReturn().response.contentAsString

        val replayResponse = mockMvc.post(path) {
            with(jwtFor("rejecting-staff", REJECT_SCOPE))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isOk() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "true") }
        }.andReturn().response.contentAsString

        assertThat(replayResponse).isEqualTo(firstResponse)
        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isOne()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(2)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)
    }

    @Test
    fun `cancellation endpoint binds the member while staff delegation is explicit`() {
        val memberId = MemberId(UUID.randomUUID())
        val requested = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = EditionId(UUID.randomUUID()),
                idempotencyKey = IdempotencyKey.parse("request-http-cancel-${UUID.randomUUID()}"),
                principal = eligibleSelfPrincipal(memberId, "http-cancel-member"),
            ),
        )
        val path = "$LOANS_PATH/${requested.result.loanId.value}/cancel"
        val key = "cancel-http-${UUID.randomUUID()}"

        mockMvc.post(path) {
            with(jwtFor("reader", "SCOPE_circulation.read", memberId.value))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post(path) {
            with(jwtFor("missing-member", CANCEL_SCOPE))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("missing_membership_claim") }
        }

        mockMvc.post(path) {
            with(jwtFor("different-member", CANCEL_SCOPE, UUID.randomUUID()))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("loan_not_found") }
        }

        val firstResponse = mockMvc.post(path) {
            with(jwtFor("cancelling-member", CANCEL_SCOPE, memberId.value))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isOk() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
            jsonPath("$.status") { value("CANCELLED") }
        }.andReturn().response.contentAsString

        val replayResponse = mockMvc.post(path) {
            with(jwtFor("cancelling-member", CANCEL_SCOPE, memberId.value))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isOk() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "true") }
        }.andReturn().response.contentAsString
        assertThat(replayResponse).isEqualTo(firstResponse)

        val delegatedMember = MemberId(UUID.randomUUID())
        val delegatedRequest = requestLoan.request(
            RequestLoanCommand(
                memberId = delegatedMember,
                editionId = EditionId(UUID.randomUUID()),
                idempotencyKey = IdempotencyKey.parse("request-staff-cancel-${UUID.randomUUID()}"),
                principal = eligibleSelfPrincipal(delegatedMember, "delegated-member"),
            ),
        )
        mockMvc.post("$LOANS_PATH/${delegatedRequest.result.loanId.value}/cancel") {
            with(jwtFor("delegated-staff", CANCEL_ON_BEHALF_SCOPE))
            header(IDEMPOTENCY_HEADER, "cancel-staff-${UUID.randomUUID()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELLED") }
        }

        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isEqualTo(2)
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(4)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(4)
    }

    @Test
    fun `identical idempotency keys are independent across authenticated principals`() {
        val sharedKey = "shared-principal-key-${UUID.randomUUID()}"
        val firstMemberId = UUID.randomUUID()
        val secondMemberId = UUID.randomUUID()
        val firstBody = requestJson(firstMemberId, UUID.randomUUID())
        val secondBody = requestJson(secondMemberId, UUID.randomUUID())
        seedEligible(MemberId(firstMemberId))
        seedEligible(MemberId(secondMemberId))

        val firstResponse = mockMvc.post(LOANS_PATH) {
            with(jwtFor("principal-one", REQUEST_SCOPE, firstMemberId, "web-client-one"))
            contentType = MediaType.APPLICATION_JSON
            content = firstBody
            header(IDEMPOTENCY_HEADER, sharedKey)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
        }.andReturn().response.contentAsString

        val secondResponse = mockMvc.post(LOANS_PATH) {
            with(jwtFor("principal-two", REQUEST_SCOPE, secondMemberId, "web-client-two"))
            contentType = MediaType.APPLICATION_JSON
            content = secondBody
            header(IDEMPOTENCY_HEADER, sharedKey)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
        }.andReturn().response.contentAsString

        val firstReplay = mockMvc.post(LOANS_PATH) {
            with(jwtFor("principal-one", REQUEST_SCOPE, firstMemberId, "web-client-one"))
            contentType = MediaType.APPLICATION_JSON
            content = firstBody
            header(IDEMPOTENCY_HEADER, sharedKey)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "true") }
        }.andReturn().response.contentAsString

        assertThat(firstResponse).isNotEqualTo(secondResponse)
        assertThat(firstReplay).isEqualTo(firstResponse)
        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isEqualTo(2)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(2)
        assertThat(
            dsl.select(CIRCULATION_IDEMPOTENCY.OWNER_FINGERPRINT)
                .from(CIRCULATION_IDEMPOTENCY)
                .fetchSet(CIRCULATION_IDEMPOTENCY.OWNER_FINGERPRINT),
        ).hasSize(2)
    }

    @Test
    fun `one principal cannot replay another principals command result`() {
        val memberId = UUID.randomUUID()
        val body = requestJson(memberId, UUID.randomUUID())
        val sharedKey = "cross-principal-replay-${UUID.randomUUID()}"
        seedEligible(MemberId(memberId))

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("staff-principal-one", ON_BEHALF_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = body
            header(IDEMPOTENCY_HEADER, sharedKey)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
        }

        mockMvc.post(LOANS_PATH) {
            with(jwtFor("staff-principal-two", ON_BEHALF_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = body
            header(IDEMPOTENCY_HEADER, sharedKey)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("open_loan_already_exists") }
        }

        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isOne()
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isOne()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isOne()
    }

    @Test
    fun `failed approval rolls back its idempotency claim and can be retried`() {
        val editionId = EditionId(UUID.randomUUID())
        val memberId = MemberId(UUID.randomUUID())
        val requested = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = editionId,
                idempotencyKey = IdempotencyKey.parse("request-no-copy-${UUID.randomUUID()}"),
                principal = eligibleSelfPrincipal(memberId, "no-copy-member"),
            ),
        )
        val approveKey = IdempotencyKey.parse("approve-no-copy-${UUID.randomUUID()}")
        val approveCommand = ApproveLoanCommand(
            loanId = requested.result.loanId,
            idempotencyKey = approveKey,
            principal = administrativePrincipal("no-copy-staff"),
        )

        assertThatThrownBy {
            approveLoan.approve(approveCommand)
        }.isInstanceOf(NoAvailableCopyException::class.java)

        val persistedRequested = dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(requested.result.loanId.value))
            .fetchSingle()
        assertThat(persistedRequested.status).isEqualTo("REQUESTED")
        assertThat(persistedRequested.copyId).isNull()
        assertThat(persistedRequested.version).isZero()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isOne()
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isOne()

        val copyId = UUID.randomUUID()
        seedCopy(copyId, editionId.value, UUID.randomUUID(), "A-0001")
        val approved = approveLoan.approve(approveCommand)

        assertThat(approved.replayed).isFalse()
        assertThat(approved.result.copyId?.value).isEqualTo(copyId)
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(2)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)
    }

    @Test
    fun `one hundred concurrent rejection retries close a request exactly once`() {
        val memberId = MemberId(UUID.randomUUID())
        val requested = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = EditionId(UUID.randomUUID()),
                idempotencyKey = IdempotencyKey.parse("request-rejection-${UUID.randomUUID()}"),
                principal = eligibleSelfPrincipal(memberId, "rejected-member"),
            ),
        )
        val rejectionKey = IdempotencyKey.parse("reject-concurrency-${UUID.randomUUID()}")
        val command = RejectLoanCommand(
            loanId = requested.result.loanId,
            idempotencyKey = rejectionKey,
            principal = administrativePrincipal("rejecting-staff"),
        )

        val rejections = runOneHundredConcurrently { rejectLoan.reject(command) }

        assertThat(rejections.count { !it.replayed }).isOne()
        assertThat(rejections.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(rejections.map { it.result }.distinct()).hasSize(1)
        assertThat(rejections.first().result.status).isEqualTo(LoanStatus.REJECTED)
        assertThat(rejections.first().result.copyId).isNull()
        assertThat(rejections.first().result.version).isEqualTo(1)

        val persisted = dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(requested.result.loanId.value))
            .fetchSingle()
        assertThat(persisted.status).isEqualTo("REJECTED")
        assertThat(persisted.rejectedAt).isNotNull()
        assertThat(persisted.copyId).isNull()
        assertOutbox(
            expectedTypes = listOf(
                "circulation.loan.requested",
                "circulation.loan.rejected",
            ),
            expectedVersions = listOf(0L, 1L),
        )
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)

        assertThatThrownBy {
            approveLoan.approve(
                ApproveLoanCommand(
                    loanId = requested.result.loanId,
                    idempotencyKey = IdempotencyKey.parse("approve-rejected-${UUID.randomUUID()}"),
                    principal = administrativePrincipal("approving-staff"),
                ),
            )
        }.isInstanceOf(LoanStateConflictException::class.java)
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(2)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)
    }

    @Test
    fun `one hundred concurrent cancellation retries close a request exactly once`() {
        val memberId = MemberId(UUID.randomUUID())
        val requested = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = EditionId(UUID.randomUUID()),
                idempotencyKey = IdempotencyKey.parse("request-cancellation-${UUID.randomUUID()}"),
                principal = eligibleSelfPrincipal(memberId, "cancelled-member"),
            ),
        )
        val command = CancelLoanCommand(
            loanId = requested.result.loanId,
            idempotencyKey = IdempotencyKey.parse("cancel-concurrency-${UUID.randomUUID()}"),
            principal = eligibleSelfPrincipal(memberId, "cancelled-member"),
        )

        val cancellations = runOneHundredConcurrently { cancelLoan.cancel(command) }

        assertThat(cancellations.count { !it.replayed }).isOne()
        assertThat(cancellations.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(cancellations.map { it.result }.distinct()).hasSize(1)
        assertThat(cancellations.first().result.status).isEqualTo(LoanStatus.CANCELLED)
        assertThat(cancellations.first().result.copyId).isNull()
        assertThat(cancellations.first().result.version).isEqualTo(1)

        val persisted = dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(requested.result.loanId.value))
            .fetchSingle()
        assertThat(persisted.status).isEqualTo("CANCELLED")
        assertThat(persisted.copyId).isNull()
        assertOutbox(
            expectedTypes = listOf(
                "circulation.loan.requested",
                "circulation.loan.cancelled",
            ),
            expectedVersions = listOf(0L, 1L),
        )
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)

        assertThatThrownBy {
            rejectLoan.reject(
                RejectLoanCommand(
                    loanId = requested.result.loanId,
                    idempotencyKey = IdempotencyKey.parse("reject-cancelled-${UUID.randomUUID()}"),
                    principal = administrativePrincipal("rejecting-staff"),
                ),
            )
        }.isInstanceOf(LoanStateConflictException::class.java)
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(2)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)
    }

    @Test
    fun `one hundred concurrent approval and return retries each mutate exactly once`() {
        val editionId = EditionId(UUID.randomUUID())
        val memberId = MemberId(UUID.randomUUID())
        val branchId = UUID.randomUUID()
        val firstCopyId = UUID.randomUUID()
        val secondCopyId = UUID.randomUUID()
        val memberPrincipal = eligibleSelfPrincipal(memberId, "concurrent-member")
        val staffPrincipal = administrativePrincipal("concurrent-staff")
        seedCopy(secondCopyId, editionId.value, branchId, "B-0002")
        seedCopy(firstCopyId, editionId.value, branchId, "A-0001")

        val requestKey = IdempotencyKey.parse("request-concurrency-${UUID.randomUUID()}")
        val requested = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = editionId,
                idempotencyKey = requestKey,
                principal = memberPrincipal,
            ),
        )
        val requestReplay = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = editionId,
                idempotencyKey = requestKey,
                principal = memberPrincipal,
            ),
        )
        assertThat(requestReplay.replayed).isTrue()
        assertThat(requestReplay.result).isEqualTo(requested.result)

        val approveKey = IdempotencyKey.parse("approve-concurrency-${UUID.randomUUID()}")
        val approvals = runOneHundredConcurrently {
            approveLoan.approve(
                ApproveLoanCommand(
                    loanId = requested.result.loanId,
                    idempotencyKey = approveKey,
                    principal = staffPrincipal,
                ),
            )
        }

        assertThat(approvals).hasSize(CONCURRENT_COMMANDS)
        assertThat(approvals.count { !it.replayed }).isOne()
        assertThat(approvals.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(approvals.map { it.result }.distinct()).containsExactly(approvals.first().result)

        val approved = approvals.first().result
        assertThat(approved.status).isEqualTo(LoanStatus.ACTIVE)
        assertThat(approved.copyId?.value).isEqualTo(firstCopyId)
        assertThat(approved.version).isEqualTo(1)
        assertThat(requireNotNull(approved.dueAt)).isEqualTo(
            requireNotNull(approved.checkedOutAt).plusSeconds(TimeUnit.DAYS.toSeconds(14)),
        )

        assertCopy(firstCopyId, "ON_LOAN", 1)
        assertCopy(secondCopyId, "AVAILABLE", 0)
        assertLoan(approved.loanId.value, "ACTIVE", firstCopyId, 1)
        assertOutbox(
            expectedTypes = listOf(
                "circulation.loan.requested",
                "circulation.loan.approved",
            ),
            expectedVersions = listOf(0L, 1L),
        )
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)

        val returnKey = IdempotencyKey.parse("return-concurrency-${UUID.randomUUID()}")
        val returns = runOneHundredConcurrently {
            returnLoan.returnLoan(
                ReturnLoanCommand(
                    loanId = approved.loanId,
                    idempotencyKey = returnKey,
                    principal = staffPrincipal,
                ),
            )
        }
        val returnReplay = returnLoan.returnLoan(
            ReturnLoanCommand(
                loanId = approved.loanId,
                idempotencyKey = returnKey,
                principal = staffPrincipal,
            ),
        )
        val returned = returns.first()

        assertThat(returns).hasSize(CONCURRENT_COMMANDS)
        assertThat(returns.count { !it.replayed }).isOne()
        assertThat(returns.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(returns.map { it.result }.distinct()).containsExactly(returned.result)
        assertThat(returned.result.status).isEqualTo(LoanStatus.RETURNED)
        assertThat(returned.result.version).isEqualTo(2)
        assertThat(returnReplay.replayed).isTrue()
        assertThat(returnReplay.result).isEqualTo(returned.result)
        assertCopy(firstCopyId, "AVAILABLE", 2)
        assertCopy(secondCopyId, "AVAILABLE", 0)
        assertLoan(approved.loanId.value, "RETURNED", firstCopyId, 2)
        assertOutbox(
            expectedTypes = listOf(
                "circulation.loan.requested",
                "circulation.loan.approved",
                "circulation.loan.returned",
            ),
            expectedVersions = listOf(0L, 1L, 2L),
        )
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(3)

        assertThatThrownBy {
            returnLoan.returnLoan(
                ReturnLoanCommand(
                    loanId = approved.loanId,
                    idempotencyKey =
                        IdempotencyKey.parse("return-after-return-${UUID.randomUUID()}"),
                    principal = staffPrincipal,
                ),
            )
        }.isInstanceOf(LoanStateConflictException::class.java)

        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(3)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(3)
    }

    private fun jwtFor(
        subject: String,
        authority: String,
        membershipClaim: Any? = null,
        clientId: String = TEST_CLIENT_ID,
    ): RequestPostProcessor = jwt()
        .jwt { builder ->
            builder
                .subject(subject)
                .claim("iss", TEST_ISSUER)
                .claim("client_id", clientId)
            if (membershipClaim != null) {
                builder.claim(
                    "membership_id",
                    if (membershipClaim is UUID) membershipClaim.toString() else membershipClaim,
                )
            }
        }
        .authorities(SimpleGrantedAuthority(authority))

    private fun selfPrincipal(memberId: MemberId, subject: String): CommandPrincipal =
        CommandPrincipal(
            idempotencyOwner = idempotencyOwner(subject),
            membershipId = memberId,
            canActOnBehalf = false,
        )

    private fun eligibleSelfPrincipal(memberId: MemberId, subject: String): CommandPrincipal {
        seedEligible(memberId)
        return selfPrincipal(memberId, subject)
    }

    private fun seedEligible(memberId: MemberId) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(CIRCULATION_MEMBER_ELIGIBILITY)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID, memberId.value)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.STATUS, "ELIGIBLE")
            .set(CIRCULATION_MEMBER_ELIGIBILITY.REASON_CODE, null as String?)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION, 0L)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_OCCURRED_AT, now)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.CREATED_AT, now)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    private fun administrativePrincipal(subject: String): CommandPrincipal =
        CommandPrincipal(
            idempotencyOwner = idempotencyOwner(subject),
            membershipId = null,
            canActOnBehalf = false,
        )

    private fun idempotencyOwner(subject: String): IdempotencyOwner =
        IdempotencyOwner.fromIdentity(
            issuer = TEST_ISSUER,
            subject = subject,
            authorizedParty = null,
            clientId = TEST_CLIENT_ID,
        )

    private fun runOneHundredConcurrently(action: () -> CommandExecution): List<CommandExecution> {
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..CONCURRENT_COMMANDS).map {
                executor.submit<CommandExecution> {
                    start.await()
                    action()
                }
            }
            start.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }
        }
    }

    private fun seedCopy(
        copyId: UUID,
        editionId: UUID,
        branchId: UUID,
        barcode: String,
    ) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(CIRCULATION_COPY)
            .set(CIRCULATION_COPY.ID, copyId)
            .set(CIRCULATION_COPY.EDITION_ID, editionId)
            .set(CIRCULATION_COPY.BRANCH_ID, branchId)
            .set(CIRCULATION_COPY.BARCODE, barcode)
            .set(CIRCULATION_COPY.STATUS, "AVAILABLE")
            .set(CIRCULATION_COPY.VERSION, 0L)
            .set(CIRCULATION_COPY.CREATED_AT, now)
            .set(CIRCULATION_COPY.UPDATED_AT, now)
            .execute()
    }

    private fun assertCopy(copyId: UUID, status: String, version: Long) {
        val copy = dsl.selectFrom(CIRCULATION_COPY)
            .where(CIRCULATION_COPY.ID.eq(copyId))
            .fetchSingle()
        assertThat(copy.status).isEqualTo(status)
        assertThat(copy.version).isEqualTo(version)
    }

    private fun assertLoan(loanId: UUID, status: String, copyId: UUID, version: Long) {
        val loan = dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(loanId))
            .fetchSingle()
        assertThat(loan.status).isEqualTo(status)
        assertThat(loan.copyId).isEqualTo(copyId)
        assertThat(loan.version).isEqualTo(version)
    }

    private fun assertOutbox(expectedTypes: List<String>, expectedVersions: List<Long>) {
        val events = dsl.selectFrom(OUTBOX_EVENT)
            .orderBy(OUTBOX_EVENT.AGGREGATE_VERSION.asc())
            .fetch()
        assertThat(events.map { it.eventType }).containsExactlyElementsOf(expectedTypes)
        assertThat(events.map { it.aggregateVersion }).containsExactlyElementsOf(expectedVersions)
        assertThat(events.map { it.eventVersion }.distinct()).containsExactly(1)
        assertThat(events.map { it.payload.data() }).allMatch { it.contains("\"loanId\"") }
    }

    private fun requestJson(memberId: UUID, editionId: UUID): String =
        """{"memberId":"$memberId","editionId":"$editionId"}"""

    private companion object {
        const val LOANS_PATH = "/api/v1/circulation/loans"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val REQUEST_SCOPE = "SCOPE_circulation.loan.request"
        const val ON_BEHALF_SCOPE = "SCOPE_circulation.loan.request.on-behalf"
        const val APPROVE_SCOPE = "SCOPE_circulation.loan.approve"
        const val REJECT_SCOPE = "SCOPE_circulation.loan.reject"
        const val CANCEL_SCOPE = "SCOPE_circulation.loan.cancel"
        const val CANCEL_ON_BEHALF_SCOPE = "SCOPE_circulation.loan.cancel.on-behalf"
        const val RETURN_SCOPE = "SCOPE_circulation.loan.return"
        const val CONCURRENT_COMMANDS = 100
        const val TEST_ISSUER = "https://issuer.example.test"
        const val TEST_CLIENT_ID = "circulation-integration-test"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("circulation")
            .withUsername("circulation")
            .withPassword("integration-test-only")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
