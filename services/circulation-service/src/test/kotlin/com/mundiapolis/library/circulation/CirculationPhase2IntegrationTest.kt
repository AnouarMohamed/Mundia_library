package com.mundiapolis.library.circulation

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_COPY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_CONSUMER_INBOX
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE_LEDGER_ENTRY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_INVENTORY_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_INVENTORY_AUDIT_ENTRY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_MEMBER_ELIGIBILITY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_RESERVATION
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.EligibilityEventDisposition
import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.BrokerPublishAcknowledgement
import com.mundiapolis.library.circulation.application.model.DuplicatePaymentReferenceException
import com.mundiapolis.library.circulation.application.model.FineBalanceConflictException
import com.mundiapolis.library.circulation.application.model.FineCurrencyMismatchException
import com.mundiapolis.library.circulation.application.model.FineNarrative
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.InvalidFineCurrencyException
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.LoanOverdueException
import com.mundiapolis.library.circulation.application.model.MemberEligibilityUnavailableException
import com.mundiapolis.library.circulation.application.model.MemberNotEligibleException
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityEvent
import com.mundiapolis.library.circulation.application.model.MembershipEventConflictException
import com.mundiapolis.library.circulation.application.model.MembershipEventGapException
import com.mundiapolis.library.circulation.application.model.PaymentReference
import com.mundiapolis.library.circulation.application.model.RenewalLimitReachedException
import com.mundiapolis.library.circulation.application.model.ReservationNotFoundException
import com.mundiapolis.library.circulation.application.model.PolicyRevisionConflictException
import com.mundiapolis.library.circulation.application.model.UpdateCirculationPolicyValues
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ApplyMembershipEligibilityEventUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ChangeCopyConditionCommand
import com.mundiapolis.library.circulation.application.port.inbound.ChangeCopyConditionUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentCommand
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RegisterCopyCommand
import com.mundiapolis.library.circulation.application.port.inbound.RegisterCopyUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RelocateCopyCommand
import com.mundiapolis.library.circulation.application.port.inbound.RelocateCopyUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ReturnLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.FulfillReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.FulfillReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ExpireReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.ExpireReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationPolicyQuery
import com.mundiapolis.library.circulation.application.port.inbound.PlaceReservationCommand
import com.mundiapolis.library.circulation.application.port.inbound.PlaceReservationUseCase
import com.mundiapolis.library.circulation.application.port.inbound.UpdateCirculationPolicyCommand
import com.mundiapolis.library.circulation.application.port.inbound.UpdateCirculationPolicyUseCase
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.application.port.outbound.OutboxDeliveryStore
import com.mundiapolis.library.circulation.application.port.outbound.RateLimitStore
import com.mundiapolis.library.circulation.application.service.ReservationExpiryService
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.EligibilityReasonCode
import com.mundiapolis.library.circulation.domain.model.BranchId
import com.mundiapolis.library.circulation.domain.model.CopyBarcode
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.CopyStatus
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryType
import com.mundiapolis.library.circulation.domain.model.FineStatus
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import com.mundiapolis.library.circulation.domain.model.InventoryReason
import com.mundiapolis.library.circulation.domain.model.ShelfLocation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.hamcrest.Matchers.matchesPattern
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataIntegrityViolationException
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
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "app.security.jwt.issuer=https://issuer.example.test",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.audience=circulation-api",
        "app.circulation.idempotency-retention=P1D",
        "spring.datasource.hikari.maximum-pool-size=24",
        "spring.datasource.hikari.connection-timeout=10000",
    ],
)
class CirculationPhase2IntegrationTest {
    @Autowired
    private lateinit var requestLoan: RequestLoanUseCase

    @Autowired
    private lateinit var approveLoan: ApproveLoanUseCase

    @Autowired
    private lateinit var renewLoan: RenewLoanUseCase

    @Autowired
    private lateinit var returnLoan: ReturnLoanUseCase

    @Autowired
    private lateinit var placeReservation: PlaceReservationUseCase

    @Autowired
    private lateinit var fulfillReservation: FulfillReservationUseCase

    @Autowired
    private lateinit var expireReservation: ExpireReservationUseCase

    @Autowired
    private lateinit var getCirculationPolicy: GetCirculationPolicyQuery

    @Autowired
    private lateinit var updateCirculationPolicy: UpdateCirculationPolicyUseCase

    @Autowired
    private lateinit var applyMembershipEligibility: ApplyMembershipEligibilityEventUseCase

    @Autowired
    private lateinit var assessFine: AssessFineUseCase

    @Autowired
    private lateinit var recordFinePayment: RecordFinePaymentUseCase

    @Autowired
    private lateinit var adjustFine: AdjustFineUseCase

    @Autowired
    private lateinit var registerCopy: RegisterCopyUseCase

    @Autowired
    private lateinit var changeCopyCondition: ChangeCopyConditionUseCase

    @Autowired
    private lateinit var relocateCopy: RelocateCopyUseCase

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var transactionRunner: TransactionRunner

    @Autowired
    private lateinit var outboxDeliveryStore: OutboxDeliveryStore

    @Autowired
    private lateinit var rateLimitStore: RateLimitStore

    @Autowired
    private lateinit var reservationExpiryService: ReservationExpiryService

    @BeforeEach
    fun cleanCommandData() {
        dsl.execute(
            "UPDATE circulation_policy_current " +
                "SET revision_id = CAST('00000000-0000-0000-0000-000000000001' AS uuid)",
        )
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
                    circulation_reservation_idempotency,
                    circulation_reservation,
                    circulation_policy_idempotency,
                    circulation_rate_limit_bucket,
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
        dsl.execute("ALTER TABLE circulation_policy_revision DISABLE TRIGGER USER")
        try {
            dsl.execute(
                "DELETE FROM circulation_policy_revision " +
                    "WHERE revision_id <> CAST(? AS uuid)",
                POLICY_SEED_REVISION.toString(),
            )
        } finally {
            dsl.execute("ALTER TABLE circulation_policy_revision ENABLE TRIGGER USER")
        }
    }

    @Test
    fun `copy registration relocation and condition changes are replay safe and audited`() {
        val copyId = CopyId(UUID.randomUUID())
        val editionId = EditionId(UUID.randomUUID())
        val branchId = BranchId(UUID.randomUUID())
        val staff = administrativePrincipal("inventory-staff")
        val registrationKey = IdempotencyKey.parse("register-copy-${UUID.randomUUID()}")
        val registration = RegisterCopyCommand(
            copyId = copyId,
            editionId = editionId,
            branchId = branchId,
            barcode = CopyBarcode.parse("INV-${UUID.randomUUID()}"),
            shelfLocation = ShelfLocation.parse("A-01"),
            reason = InventoryReason.parse("New acquisition received"),
            idempotencyKey = registrationKey,
            principal = staff,
        )

        val registrations = runOneHundredConcurrently {
            registerCopy.register(registration)
        }
        assertThat(registrations.count { !it.replayed }).isOne()
        assertThat(registrations.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(registrations.map { it.result }.distinct()).hasSize(1)

        assertThatThrownBy {
            registerCopy.register(
                registration.copy(
                    reason = InventoryReason.parse("Different acquisition reason"),
                ),
            )
        }.isInstanceOf(com.mundiapolis.library.circulation.application.model.IdempotencyKeyConflictException::class.java)

        val relocated = relocateCopy.relocate(
            RelocateCopyCommand(
                copyId = copyId,
                branchId = BranchId(UUID.randomUUID()),
                shelfLocation = ShelfLocation.parse("B-12"),
                reason = InventoryReason.parse("Moved to the science collection"),
                idempotencyKey = IdempotencyKey.parse("relocate-copy-${UUID.randomUUID()}"),
                principal = staff,
            ),
        )
        val damaged = changeCopyCondition.changeCondition(
            ChangeCopyConditionCommand(
                copyId = copyId,
                target = CopyStatus.DAMAGED,
                reason = InventoryReason.parse("Binding damage verified by librarian"),
                idempotencyKey = IdempotencyKey.parse("damage-copy-${UUID.randomUUID()}"),
                principal = staff,
            ),
        )

        assertThat(relocated.result.version).isOne()
        assertThat(damaged.result.version).isEqualTo(2)
        assertThat(damaged.result.status).isEqualTo(CopyStatus.DAMAGED)
        assertThat(damaged.result.shelfLocation).isNull()
        assertThat(dsl.fetchCount(CIRCULATION_INVENTORY_IDEMPOTENCY)).isEqualTo(3)
        val auditEntries = dsl.selectFrom(CIRCULATION_INVENTORY_AUDIT_ENTRY)
            .where(CIRCULATION_INVENTORY_AUDIT_ENTRY.COPY_ID.eq(copyId.value))
            .orderBy(CIRCULATION_INVENTORY_AUDIT_ENTRY.COPY_VERSION)
            .fetch()
        assertThat(auditEntries.map { it.operation }).containsExactly(
            "REGISTER_COPY",
            "RELOCATE_COPY",
            "CHANGE_COPY_CONDITION",
        )
        assertThat(auditEntries.map { it.actorFingerprint }).allMatch { it?.length == 64 }
        assertThat(auditEntries.map { it.reason }).containsExactly(
            "New acquisition received",
            "Moved to the science collection",
            "Binding damage verified by librarian",
        )
        assertThatThrownBy {
            dsl.update(CIRCULATION_INVENTORY_AUDIT_ENTRY)
                .set(CIRCULATION_INVENTORY_AUDIT_ENTRY.REASON, "tampered")
                .where(CIRCULATION_INVENTORY_AUDIT_ENTRY.ID.eq(auditEntries.first().id))
                .execute()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("immutable")
        val events = dsl.selectFrom(OUTBOX_EVENT)
            .where(OUTBOX_EVENT.AGGREGATE_TYPE.eq("copy"))
            .orderBy(OUTBOX_EVENT.AGGREGATE_VERSION)
            .fetch()
        assertThat(events.map { it.eventType }).containsExactly(
            "circulation.copy.registered",
            "circulation.copy.relocated",
            "circulation.copy.condition-changed",
        )
        assertThat(events.map { it.aggregateVersion }).containsExactly(0L, 1L, 2L)
        assertThat(events.map { it.payload?.data() }).allMatch { payload ->
            payload?.contains("actorFingerprint") == true && payload.contains("reason")
        }
    }

    @Test
    fun `one hundred competing approval and inventory edits preserve single copy ownership`() {
        val copyId = CopyId(UUID.randomUUID())
        val editionId = EditionId(UUID.randomUUID())
        val staff = administrativePrincipal("inventory-race-staff")
        registerCopy.register(
            RegisterCopyCommand(
                copyId = copyId,
                editionId = editionId,
                branchId = BranchId(UUID.randomUUID()),
                barcode = CopyBarcode.parse("RACE-${UUID.randomUUID()}"),
                shelfLocation = ShelfLocation.parse("R-01"),
                reason = InventoryReason.parse("Race-test acquisition"),
                idempotencyKey = IdempotencyKey.parse("register-race-${UUID.randomUUID()}"),
                principal = staff,
            ),
        )
        val memberId = MemberId(UUID.randomUUID())
        seedEligible(memberId)
        val request = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = editionId,
                idempotencyKey = IdempotencyKey.parse("request-race-${UUID.randomUUID()}"),
                principal = selfPrincipal(memberId, "inventory-race-member"),
            ),
        )

        val outcomes = runConcurrently(CONCURRENT_COMMANDS) { index ->
            if (index % 2 == 0) {
                approveLoan.approve(
                    ApproveLoanCommand(
                        loanId = request.result.loanId,
                        idempotencyKey =
                            IdempotencyKey.parse("approve-inventory-race-$index-${UUID.randomUUID()}"),
                        principal = staff,
                    ),
                )
            } else {
                changeCopyCondition.changeCondition(
                    ChangeCopyConditionCommand(
                        copyId = copyId,
                        target = CopyStatus.DAMAGED,
                        reason = InventoryReason.parse("Concurrent condition inspection $index"),
                        idempotencyKey =
                            IdempotencyKey.parse("condition-race-$index-${UUID.randomUUID()}"),
                        principal = staff,
                    ),
                )
            }
        }

        assertThat(outcomes.count { it.isSuccess }).isOne()
        val copy = dsl.selectFrom(CIRCULATION_COPY)
            .where(CIRCULATION_COPY.ID.eq(copyId.value))
            .fetchSingle()
        val loan = dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(request.result.loanId.value))
            .fetchSingle()
        if (copy.status == CopyStatus.ON_LOAN.name) {
            assertThat(loan.status).isEqualTo(LoanStatus.ACTIVE.name)
            assertThat(loan.copyId).isEqualTo(copyId.value)
        } else {
            assertThat(copy.status).isEqualTo(CopyStatus.DAMAGED.name)
            assertThat(loan.status).isEqualTo(LoanStatus.REQUESTED.name)
            assertThat(loan.copyId).isNull()
        }
        assertThat(copy.version).isOne()
        assertThat(dsl.fetchCount(CIRCULATION_INVENTORY_IDEMPOTENCY)).isEqualTo(
            if (copy.status == CopyStatus.DAMAGED.name) 2 else 1,
        )
    }

    @Test
    fun `inventory HTTP commands enforce scope strict input and lifecycle conflicts`() {
        val copyId = UUID.randomUUID()
        val request =
            """{"copyId":"$copyId","editionId":"${UUID.randomUUID()}","branchId":"${UUID.randomUUID()}","barcode":"HTTP-$copyId","shelfLocation":"A-02","reason":"New acquisition"}"""

        mockMvc.post(COPIES_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = request
            header(IDEMPOTENCY_HEADER, "register-http-${UUID.randomUUID()}")
        }.andExpect { status { isUnauthorized() } }

        mockMvc.post(COPIES_PATH) {
            with(jwtFor("wrong-inventory-scope", ASSESS_FINE_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = request
            header(IDEMPOTENCY_HEADER, "register-http-${UUID.randomUUID()}")
        }.andExpect { status { isForbidden() } }

        mockMvc.post(COPIES_PATH) {
            with(jwtFor("inventory-registrar", REGISTER_INVENTORY_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = request
            header(IDEMPOTENCY_HEADER, "register-http-${UUID.randomUUID()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("AVAILABLE") }
            jsonPath("$.version") { value(0) }
        }

        mockMvc.post("$COPIES_PATH/$copyId/condition") {
            with(jwtFor("inventory-condition", CONDITION_INVENTORY_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"ON_LOAN","reason":"Attempted manual checkout"}"""
            header(IDEMPOTENCY_HEADER, "condition-http-${UUID.randomUUID()}")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("copy_state_conflict") }
        }

        mockMvc.post(COPIES_PATH) {
            with(jwtFor("inventory-registrar", REGISTER_INVENTORY_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = request.dropLast(1) + ",\"unexpected\":true}"
            header(IDEMPOTENCY_HEADER, "register-http-${UUID.randomUUID()}")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `expired outbox lease is replayed and stale owner cannot acknowledge it`() {
        createActiveLoan(MemberId(UUID.randomUUID()))
        val claimTime = Instant.now().plusSeconds(5)
        val firstClaim =
            outboxDeliveryStore.claimBatch(
                owner = "publisher-a",
                now = claimTime,
                leaseExpiresAt = claimTime.plusSeconds(30),
                batchSize = 10,
            )
        assertThat(firstClaim).hasSize(1)

        assertThat(
            outboxDeliveryStore.claimBatch(
                owner = "publisher-b",
                now = claimTime.plusSeconds(1),
                leaseExpiresAt = claimTime.plusSeconds(31),
                batchSize = 10,
            ),
        ).isEmpty()

        val replay =
            outboxDeliveryStore.claimBatch(
                owner = "publisher-b",
                now = claimTime.plusSeconds(31),
                leaseExpiresAt = claimTime.plusSeconds(61),
                batchSize = 10,
            ).single()
        assertThat(replay.id).isEqualTo(firstClaim.single().id)
        assertThat(replay.leaseToken).isNotEqualTo(firstClaim.single().leaseToken)
        assertThat(replay.deliveryAttempt).isEqualTo(2)

        val acknowledgement =
            BrokerPublishAcknowledgement(
                topic = "mundia.circulation.events.v1",
                partition = 0,
                offset = 1,
            )
        assertThat(
            outboxDeliveryStore.markPublished(
                owner = "publisher-a",
                event = firstClaim.single(),
                acknowledgement = acknowledgement,
                publishedAt = claimTime.plusSeconds(32),
            ),
        ).isFalse()
        assertThat(
            outboxDeliveryStore.markPublished(
                owner = "publisher-b",
                event = replay,
                acknowledgement = acknowledgement,
                publishedAt = claimTime.plusSeconds(32),
            ),
        ).isTrue()

        val nextAggregateVersion =
            outboxDeliveryStore.claimBatch(
                owner = "publisher-b",
                now = claimTime.plusSeconds(33),
                leaseExpiresAt = claimTime.plusSeconds(63),
                batchSize = 10,
            ).single()
        assertThat(nextAggregateVersion.aggregateVersion)
            .isGreaterThan(replay.aggregateVersion)
    }

    @Test
    fun `renewal is member-bound idempotent serialized and policy limited`() {
        val memberId = MemberId(UUID.randomUUID())
        val active = createActiveLoan(memberId)
        val renewalKey = IdempotencyKey.parse("renew-concurrency-${UUID.randomUUID()}")

        mockMvc.post("$LOANS_PATH/${active.value}/renew") {
            with(jwtFor("wrong-member", RENEW_SCOPE, UUID.randomUUID()))
            header(IDEMPOTENCY_HEADER, "renew-cross-member-${UUID.randomUUID()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("loan_not_found") }
        }

        mockMvc.post("$LOANS_PATH/${UUID.randomUUID()}/renew") {
            with(jwtFor("wrong-member", RENEW_SCOPE, UUID.randomUUID()))
            header(IDEMPOTENCY_HEADER, "renew-missing-${UUID.randomUUID()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("loan_not_found") }
        }

        val renewals = runOneHundredConcurrently {
            renewLoan.renew(
                RenewLoanCommand(
                    loanId = active,
                    idempotencyKey = renewalKey,
                    principal = selfPrincipal(memberId, "renew-member"),
                ),
            )
        }

        assertThat(renewals.count { !it.replayed }).isOne()
        assertThat(renewals.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(renewals.map { it.result }.distinct()).hasSize(1)
        val firstRenewal = renewals.first().result
        assertThat(firstRenewal.status).isEqualTo(LoanStatus.ACTIVE)
        assertThat(firstRenewal.renewalCount).isOne()
        assertThat(firstRenewal.version).isEqualTo(2)

        val distinctKeyRenewals = runConcurrently(CONCURRENT_COMMANDS) { index ->
            renewLoan.renew(
                RenewLoanCommand(
                    loanId = active,
                    idempotencyKey =
                        IdempotencyKey.parse("renew-distinct-$index-${UUID.randomUUID()}"),
                    principal = selfPrincipal(memberId, "renew-member"),
                ),
            )
        }
        assertThat(distinctKeyRenewals.count { it.isSuccess }).isOne()
        assertThat(
            distinctKeyRenewals.count {
                it.exceptionOrNull() is RenewalLimitReachedException
            },
        ).isEqualTo(CONCURRENT_COMMANDS - 1)

        mockMvc.post("$LOANS_PATH/${active.value}/renew") {
            with(jwtFor("renew-staff", RENEW_ON_BEHALF_SCOPE))
            header(IDEMPOTENCY_HEADER, "renew-limit-${UUID.randomUUID()}")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("renewal_limit_reached") }
        }

        val persisted = dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(active.value))
            .fetchSingle()
        assertThat(persisted.renewalCount).isEqualTo(2)
        assertThat(persisted.version).isEqualTo(3)
        assertThat(
            dsl.selectFrom(OUTBOX_EVENT)
                .where(OUTBOX_EVENT.AGGREGATE_TYPE.eq("loan"))
                .orderBy(OUTBOX_EVENT.AGGREGATE_VERSION)
                .fetch(OUTBOX_EVENT.EVENT_TYPE),
        ).containsExactly(
            "circulation.loan.requested",
            "circulation.loan.approved",
            "circulation.loan.renewed",
            "circulation.loan.renewed",
        )
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(4)
    }

    @Test
    fun `overdue loan renewal fails atomically without retaining idempotency claim`() {
        val memberId = MemberId(UUID.randomUUID())
        val active = createActiveLoan(memberId)
        val overdueCheckout = Instant.now().minusSeconds(TimeUnit.DAYS.toSeconds(2))
        dsl.update(CIRCULATION_LOAN)
            .set(CIRCULATION_LOAN.CHECKED_OUT_AT, overdueCheckout.atOffset(ZoneOffset.UTC))
            .set(
                CIRCULATION_LOAN.DUE_AT,
                overdueCheckout.plusSeconds(TimeUnit.DAYS.toSeconds(1)).atOffset(ZoneOffset.UTC),
            )
            .where(CIRCULATION_LOAN.ID.eq(active.value))
            .execute()

        assertThatThrownBy {
            renewLoan.renew(
                RenewLoanCommand(
                    loanId = active,
                    idempotencyKey = IdempotencyKey.parse("renew-overdue-${UUID.randomUUID()}"),
                    principal = selfPrincipal(memberId, "overdue-member"),
                ),
            )
        }.isInstanceOf(LoanOverdueException::class.java)

        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(2)
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(2)
        assertThat(
            dsl.select(CIRCULATION_LOAN.RENEWAL_COUNT)
                .from(CIRCULATION_LOAN)
                .where(CIRCULATION_LOAN.ID.eq(active.value))
                .fetchSingle(CIRCULATION_LOAN.RENEWAL_COUNT),
        ).isZero()
    }

    @Test
    fun `command transactions use read committed with bounded lock and statement waits`() {
        val settings = transactionRunner.required {
            dsl.fetchSingle(
                """
                SELECT
                    current_setting('transaction_isolation') AS isolation,
                    current_setting('lock_timeout') AS lock_timeout,
                    current_setting('statement_timeout') AS statement_timeout,
                    current_setting('idle_in_transaction_session_timeout') AS idle_timeout
                """.trimIndent(),
            )
        }

        assertThat(settings.get("isolation", String::class.java)).isEqualTo("read committed")
        assertThat(settings.get("lock_timeout", String::class.java)).isEqualTo("3s")
        assertThat(settings.get("statement_timeout", String::class.java)).isEqualTo("10s")
        assertThat(settings.get("idle_timeout", String::class.java)).isEqualTo("10s")
    }

    @Test
    fun `renewal replay is bound to the current membership authorization context`() {
        val memberId = MemberId(UUID.randomUUID())
        val active = createActiveLoan(memberId)
        val key = "renew-authorization-context-${UUID.randomUUID()}"

        mockMvc.post("$LOANS_PATH/${active.value}/renew") {
            with(jwtFor("renew-context-actor", RENEW_ON_BEHALF_SCOPE))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isOk() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
            jsonPath("$.renewalCount") { value(1) }
        }

        mockMvc.post("$LOANS_PATH/${active.value}/renew") {
            with(jwtFor("renew-context-actor", RENEW_SCOPE, UUID.randomUUID()))
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("idempotency_key_conflict") }
        }

        val persisted = dsl.selectFrom(CIRCULATION_LOAN)
            .where(CIRCULATION_LOAN.ID.eq(active.value))
            .fetchSingle()
        assertThat(persisted.renewalCount).isOne()
        assertThat(
            dsl.selectFrom(OUTBOX_EVENT)
                .where(OUTBOX_EVENT.AGGREGATE_TYPE.eq("loan"))
                .fetch(),
        ).hasSize(3)
    }

    @Test
    fun `phase2 migrations upgrade V3 idempotency without rewriting existing rows`() {
        val schema = "phase2_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("3"))
                .load()
                .migrate()

            val completedKey = "legacy-completed-${UUID.randomUUID()}"
            val pendingKey = "legacy-pending-${UUID.randomUUID()}"
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO "$schema".circulation_idempotency (
                            owner_fingerprint,
                            idempotency_key,
                            operation,
                            request_fingerprint,
                            response_status,
                            loan_id,
                            member_id,
                            edition_id,
                            loan_status,
                            requested_at,
                            loan_version,
                            created_at,
                            completed_at,
                            expires_at
                        )
                        VALUES (
                            '${"a".repeat(64)}',
                            '$completedKey',
                            'REQUEST_LOAN',
                            '${"b".repeat(64)}',
                            201,
                            '${UUID.randomUUID()}',
                            '${UUID.randomUUID()}',
                            '${UUID.randomUUID()}',
                            'REQUESTED',
                            CURRENT_TIMESTAMP,
                            0,
                            CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP + INTERVAL '1 day'
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO "$schema".circulation_idempotency (
                            owner_fingerprint,
                            idempotency_key,
                            operation,
                            request_fingerprint,
                            created_at,
                            expires_at
                        )
                        VALUES (
                            '${"c".repeat(64)}',
                            '$pendingKey',
                            'APPROVE_LOAN',
                            '${"d".repeat(64)}',
                            CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP + INTERVAL '1 day'
                        )
                        """.trimIndent(),
                    )
                }
            }

            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT renewal_count
                        FROM "$schema".circulation_idempotency
                        WHERE idempotency_key = '$completedKey'
                        """.trimIndent(),
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        result.getInt("renewal_count")
                        assertThat(result.wasNull()).isTrue()
                    }
                    statement.executeQuery(
                        """
                        SELECT renewal_count
                        FROM "$schema".circulation_idempotency
                        WHERE idempotency_key = '$pendingKey'
                        """.trimIndent(),
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        result.getInt("renewal_count")
                        assertThat(result.wasNull()).isTrue()
                    }
                    statement.executeQuery(
                        """
                        SELECT COUNT(*) AS table_count
                        FROM information_schema.tables
                        WHERE table_schema = '$schema'
                          AND table_name IN (
                              'circulation_fine',
                              'circulation_fine_ledger_entry'
                          )
                        """.trimIndent(),
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getInt("table_count")).isEqualTo(2)
                    }
                    statement.executeQuery(
                        """
                        SELECT COUNT(*) AS invalid_constraint_count
                        FROM pg_constraint constraint_record
                        JOIN pg_namespace namespace_record
                          ON namespace_record.oid = constraint_record.connamespace
                        WHERE namespace_record.nspname = '$schema'
                          AND NOT constraint_record.convalidated
                        """.trimIndent(),
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getInt("invalid_constraint_count")).isZero()
                    }
                }
            }
        } finally {
            dsl.execute("""DROP SCHEMA IF EXISTS "$schema" CASCADE""")
        }
    }

    @Test
    fun `fine commands enforce scopes and exact caller-bound HTTP replay`() {
        val memberId = MemberId(UUID.randomUUID())
        val active = createActiveLoan(memberId)
        val body =
            """{"loanId":"${active.value}","amountMinor":5000,"reason":"Overdue return"}"""
        val key = "fine-http-${UUID.randomUUID()}"

        mockMvc.post(FINES_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post(FINES_PATH) {
            with(jwtFor("fine-reader", "SCOPE_circulation.read"))
            contentType = MediaType.APPLICATION_JSON
            content = body
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isForbidden() }
        }

        val initial = mockMvc.post(FINES_PATH) {
            with(jwtFor("fine-assessor", ASSESS_FINE_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = body
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
            jsonPath("$.loanId") { value(active.value.toString()) }
            jsonPath("$.memberId") { value(memberId.value.toString()) }
            jsonPath("$.currency") { value("MAD") }
            jsonPath("$.balanceMinor") { value(5000) }
            jsonPath("$.ledgerEntryType") { value("ASSESSMENT") }
        }.andReturn().response.contentAsString

        val replay = mockMvc.post(FINES_PATH) {
            with(jwtFor("fine-assessor", ASSESS_FINE_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = body
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isCreated() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "true") }
        }.andReturn().response.contentAsString

        assertThat(replay).isEqualTo(initial)
        assertThat(dsl.fetchCount(CIRCULATION_FINE)).isOne()
        assertThat(dsl.fetchCount(CIRCULATION_FINE_LEDGER_ENTRY)).isOne()
        assertThat(
            dsl.selectFrom(OUTBOX_EVENT)
                .where(OUTBOX_EVENT.AGGREGATE_TYPE.eq("fine"))
                .fetch(),
        ).hasSize(1)
        val fineId = dsl.select(CIRCULATION_FINE.ID)
            .from(CIRCULATION_FINE)
            .fetchSingle(CIRCULATION_FINE.ID)

        mockMvc.post("$FINES_PATH/$fineId/payments") {
            with(jwtFor("fine-assessor", ASSESS_FINE_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"currency":"MAD","amountMinor":100,"externalReference":"PAY-${UUID.randomUUID()}"}"""
            header(IDEMPOTENCY_HEADER, "payment-wrong-scope-${UUID.randomUUID()}")
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post("$FINES_PATH/$fineId/adjustments") {
            with(jwtFor("payment-recorder", RECORD_PAYMENT_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = """{"currency":"MAD","deltaMinor":-100,"reason":"Approved waiver"}"""
            header(IDEMPOTENCY_HEADER, "adjust-wrong-scope-${UUID.randomUUID()}")
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post(FINES_PATH) {
            with(jwtFor("fine-assessor", ASSESS_FINE_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"loanId":"${active.value}","amountMinor":6000,"reason":"Overdue return"}"""
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("idempotency_key_conflict") }
        }

        mockMvc.post(FINES_PATH) {
            with(jwtFor("other-assessor", ASSESS_FINE_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"loanId":"${UUID.randomUUID()}","amountMinor":5000,"reason":"Other"}"""
            header(IDEMPOTENCY_HEADER, key)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("loan_not_found") }
        }
    }

    @Test
    fun `concurrent unique payments cannot overdraw a fine`() {
        val memberId = MemberId(UUID.randomUUID())
        val active = createActiveLoan(memberId)
        val staff = administrativePrincipal("concurrent-finance-staff")
        val assessed = assessFine.assess(
            AssessFineCommand(
                loanId = active,
                amountMinor = 50,
                reason = FineNarrative.parse("Overdue return"),
                idempotencyKey = IdempotencyKey.parse("assess-overdraw-${UUID.randomUUID()}"),
                principal = staff,
            ),
        )

        val payments = runConcurrently(CONCURRENT_COMMANDS) { index ->
            recordFinePayment.recordPayment(
                RecordFinePaymentCommand(
                    fineId = assessed.result.fineId,
                    currency = "MAD",
                    amountMinor = 1,
                    externalReference = PaymentReference.parse("PAY-$index-${UUID.randomUUID()}"),
                    idempotencyKey =
                        IdempotencyKey.parse("concurrent-payment-$index-${UUID.randomUUID()}"),
                    principal = staff,
                ),
            )
        }

        assertThat(payments.count { it.isSuccess }).isEqualTo(50)
        assertThat(payments.count { it.exceptionOrNull() is FineBalanceConflictException })
            .isEqualTo(50)
        val fine = dsl.selectFrom(CIRCULATION_FINE)
            .where(CIRCULATION_FINE.ID.eq(assessed.result.fineId.value))
            .fetchSingle()
        assertThat(fine.balanceMinor).isZero()
        assertThat(fine.status).isEqualTo(FineStatus.SETTLED.name)
        assertThat(fine.version).isEqualTo(50)
        val ledgerEntries = dsl.selectFrom(CIRCULATION_FINE_LEDGER_ENTRY)
            .where(CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID.eq(assessed.result.fineId.value))
            .orderBy(CIRCULATION_FINE_LEDGER_ENTRY.FINE_VERSION)
            .fetch()
        assertThat(ledgerEntries).hasSize(51)
        assertThat(ledgerEntries.map { it.fineVersion }).containsExactlyElementsOf(
            (0L..50L).toList(),
        )
        assertThat(ledgerEntries.sumOf { requireNotNull(it.deltaMinor) }).isZero()
        assertThat(
            dsl.selectFrom(OUTBOX_EVENT)
                .where(OUTBOX_EVENT.AGGREGATE_TYPE.eq("fine"))
                .fetch(),
        ).hasSize(51)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(53)
    }

    @Test
    fun `fine ledger serializes payment retries supports adjustments and rejects mutation`() {
        val memberId = MemberId(UUID.randomUUID())
        val active = createActiveLoan(memberId)
        val staff = administrativePrincipal("finance-staff")
        val assessed = assessFine.assess(
            AssessFineCommand(
                loanId = active,
                amountMinor = 5_000,
                reason = FineNarrative.parse("Overdue return"),
                idempotencyKey = IdempotencyKey.parse("assess-fine-${UUID.randomUUID()}"),
                principal = staff,
            ),
        )
        val paymentReference = PaymentReference.parse("PAY-${UUID.randomUUID()}")
        val paymentKey = IdempotencyKey.parse("record-payment-${UUID.randomUUID()}")

        val payments = runOneHundredConcurrently {
            recordFinePayment.recordPayment(
                RecordFinePaymentCommand(
                    fineId = assessed.result.fineId,
                    currency = "MAD",
                    amountMinor = 2_000,
                    externalReference = paymentReference,
                    idempotencyKey = paymentKey,
                    principal = staff,
                ),
            )
        }
        assertThat(payments.count { !it.replayed }).isOne()
        assertThat(payments.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(payments.map { it.result }.distinct()).hasSize(1)
        assertThat(payments.first().result.balanceMinor).isEqualTo(3_000)
        assertThat(payments.first().result.ledgerEntryType)
            .isEqualTo(FineLedgerEntryType.PAYMENT)

        assertThatThrownBy {
            recordFinePayment.recordPayment(
                RecordFinePaymentCommand(
                    fineId = assessed.result.fineId,
                    currency = "MAD",
                    amountMinor = 100,
                    externalReference = paymentReference,
                    idempotencyKey =
                        IdempotencyKey.parse("duplicate-payment-reference-${UUID.randomUUID()}"),
                    principal = staff,
                ),
            )
        }.isInstanceOf(DuplicatePaymentReferenceException::class.java)

        assertThatThrownBy {
            recordFinePayment.recordPayment(
                RecordFinePaymentCommand(
                    fineId = assessed.result.fineId,
                    currency = "MAD",
                    amountMinor = 3_001,
                    externalReference = PaymentReference.parse("PAY-${UUID.randomUUID()}"),
                    idempotencyKey = IdempotencyKey.parse("overpayment-${UUID.randomUUID()}"),
                    principal = staff,
                ),
            )
        }.isInstanceOf(FineBalanceConflictException::class.java)

        assertThatThrownBy {
            recordFinePayment.recordPayment(
                RecordFinePaymentCommand(
                    fineId = assessed.result.fineId,
                    currency = "USD",
                    amountMinor = 100,
                    externalReference = PaymentReference.parse("PAY-${UUID.randomUUID()}"),
                    idempotencyKey = IdempotencyKey.parse("wrong-currency-${UUID.randomUUID()}"),
                    principal = staff,
                ),
            )
        }.isInstanceOf(FineCurrencyMismatchException::class.java)

        assertThatThrownBy {
            recordFinePayment.recordPayment(
                RecordFinePaymentCommand(
                    fineId = assessed.result.fineId,
                    currency = "mad",
                    amountMinor = 100,
                    externalReference = PaymentReference.parse("PAY-${UUID.randomUUID()}"),
                    idempotencyKey = IdempotencyKey.parse("invalid-currency-${UUID.randomUUID()}"),
                    principal = staff,
                ),
            )
        }.isInstanceOf(InvalidFineCurrencyException::class.java)

        val adjusted = adjustFine.adjust(
            AdjustFineCommand(
                fineId = assessed.result.fineId,
                currency = "MAD",
                deltaMinor = -500,
                reason = FineNarrative.parse("Approved partial waiver"),
                idempotencyKey = IdempotencyKey.parse("adjust-fine-${UUID.randomUUID()}"),
                principal = staff,
            ),
        )
        assertThat(adjusted.result.balanceMinor).isEqualTo(2_500)
        assertThat(adjusted.result.status).isEqualTo(FineStatus.OPEN)
        assertThat(adjusted.result.version).isEqualTo(2)

        val fine = dsl.selectFrom(CIRCULATION_FINE)
            .where(CIRCULATION_FINE.ID.eq(assessed.result.fineId.value))
            .fetchSingle()
        val entries = dsl.selectFrom(CIRCULATION_FINE_LEDGER_ENTRY)
            .where(CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID.eq(assessed.result.fineId.value))
            .orderBy(CIRCULATION_FINE_LEDGER_ENTRY.FINE_VERSION)
            .fetch()
        assertThat(fine.balanceMinor).isEqualTo(2_500)
        assertThat(fine.version).isEqualTo(2)
        assertThat(entries.map { it.entryType })
            .containsExactly("ASSESSMENT", "PAYMENT", "ADJUSTMENT")
        assertThat(entries.sumOf { requireNotNull(it.deltaMinor) }).isEqualTo(fine.balanceMinor)
        assertThat(entries.map { it.fineVersion }).containsExactly(0L, 1L, 2L)
        assertThat(entries.map { it.actorFingerprint }).allMatch { it?.length == 64 }

        val fineEvents = dsl.selectFrom(OUTBOX_EVENT)
            .where(OUTBOX_EVENT.AGGREGATE_TYPE.eq("fine"))
            .orderBy(OUTBOX_EVENT.AGGREGATE_VERSION)
            .fetch()
        assertThat(fineEvents.map { it.eventType }).containsExactly(
            "circulation.fine.assessed",
            "circulation.fine.payment-recorded",
            "circulation.fine.adjusted",
        )
        assertThat(fineEvents.map { it.aggregateVersion }).containsExactly(0L, 1L, 2L)
        assertThat(fineEvents[1].payload?.data())
            .contains("\"externalReference\"")
            .contains(paymentReference.value)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(5)

        val entryId = entries.first().id
        assertThatThrownBy {
            dsl.update(CIRCULATION_FINE_LEDGER_ENTRY)
                .set(CIRCULATION_FINE_LEDGER_ENTRY.REASON, "tampered")
                .where(CIRCULATION_FINE_LEDGER_ENTRY.ID.eq(entryId))
                .execute()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("immutable")
        assertThatThrownBy {
            dsl.deleteFrom(CIRCULATION_FINE_LEDGER_ENTRY)
                .where(CIRCULATION_FINE_LEDGER_ENTRY.ID.eq(entryId))
                .execute()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("immutable")
        assertThatThrownBy {
            dsl.execute("TRUNCATE TABLE circulation_fine_ledger_entry")
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("immutable")
        assertThat(dsl.fetchCount(CIRCULATION_FINE_LEDGER_ENTRY)).isEqualTo(3)
    }

    @Test
    fun `deferred ledger invariant rejects direct SQL balance and ledger bypasses`() {
        val memberId = MemberId(UUID.randomUUID())
        val active = createActiveLoan(memberId)
        val assessed = assessFine.assess(
            AssessFineCommand(
                loanId = active,
                amountMinor = 5_000,
                reason = FineNarrative.parse("Overdue return"),
                idempotencyKey = IdempotencyKey.parse("assess-ledger-guard-${UUID.randomUUID()}"),
                principal = administrativePrincipal("ledger-guard-staff"),
            ),
        )
        val fineId = assessed.result.fineId.value
        val now = Instant.now().atOffset(ZoneOffset.UTC)

        assertThatThrownBy {
            dsl.transaction { configuration ->
                configuration.dsl()
                    .update(CIRCULATION_FINE)
                    .set(CIRCULATION_FINE.BALANCE_MINOR, 4_900)
                    .set(CIRCULATION_FINE.UPDATED_AT, now)
                    .where(CIRCULATION_FINE.ID.eq(fineId))
                    .execute()
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("does not match its immutable ledger")

        assertThatThrownBy {
            dsl.transaction { configuration ->
                configuration.dsl()
                    .insertInto(CIRCULATION_FINE_LEDGER_ENTRY)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ID, UUID.randomUUID())
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID, fineId)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_VERSION, 1L)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ENTRY_TYPE, "ADJUSTMENT")
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.DELTA_MINOR, -100L)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ACTOR_FINGERPRINT, "e".repeat(64))
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.REASON, "Unpaired direct ledger entry")
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.OCCURRED_AT, now)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.CREATED_AT, now)
                    .execute()
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("does not match its immutable ledger")

        assertThatThrownBy {
            dsl.transaction { configuration ->
                val tx = configuration.dsl()
                tx.update(CIRCULATION_FINE)
                    .set(CIRCULATION_FINE.BALANCE_MINOR, 4_900)
                    .set(CIRCULATION_FINE.VERSION, 1L)
                    .set(CIRCULATION_FINE.UPDATED_AT, now)
                    .where(CIRCULATION_FINE.ID.eq(fineId))
                    .execute()
                tx.insertInto(CIRCULATION_FINE_LEDGER_ENTRY)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ID, UUID.randomUUID())
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID, fineId)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_VERSION, 1L)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ENTRY_TYPE, "ADJUSTMENT")
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.DELTA_MINOR, -50L)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ACTOR_FINGERPRINT, "e".repeat(64))
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.REASON, "Mismatched direct delta")
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.OCCURRED_AT, now)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.CREATED_AT, now)
                    .execute()
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("does not match its immutable ledger")

        assertThatThrownBy {
            dsl.transaction { configuration ->
                val tx = configuration.dsl()
                tx.update(CIRCULATION_FINE)
                    .set(CIRCULATION_FINE.BALANCE_MINOR, 4_900)
                    .set(CIRCULATION_FINE.VERSION, 2L)
                    .set(CIRCULATION_FINE.UPDATED_AT, now)
                    .where(CIRCULATION_FINE.ID.eq(fineId))
                    .execute()
                tx.insertInto(CIRCULATION_FINE_LEDGER_ENTRY)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ID, UUID.randomUUID())
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID, fineId)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.FINE_VERSION, 2L)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ENTRY_TYPE, "ADJUSTMENT")
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.DELTA_MINOR, -100L)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.ACTOR_FINGERPRINT, "e".repeat(64))
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.REASON, "Skipped direct ledger version")
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.OCCURRED_AT, now)
                    .set(CIRCULATION_FINE_LEDGER_ENTRY.CREATED_AT, now)
                    .execute()
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("does not match its immutable ledger")

        val persistedFine = dsl.selectFrom(CIRCULATION_FINE)
            .where(CIRCULATION_FINE.ID.eq(fineId))
            .fetchSingle()
        assertThat(persistedFine.balanceMinor).isEqualTo(5_000)
        assertThat(persistedFine.version).isZero()
        assertThat(
            dsl.selectFrom(CIRCULATION_FINE_LEDGER_ENTRY)
                .where(CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID.eq(fineId))
                .fetch(),
        ).hasSize(1)
    }

    @Test
    fun `oversized command bodies are rejected before JSON binding`() {
        val oversizedReason = "a".repeat(17 * 1024)

        mockMvc.post(FINES_PATH) {
            with(jwtFor("body-limit-assessor", ASSESS_FINE_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"loanId":"${UUID.randomUUID()}","amountMinor":100,"reason":"$oversizedReason"}"""
            header(IDEMPOTENCY_HEADER, "oversized-command-${UUID.randomUUID()}")
        }.andExpect {
            status { isContentTooLarge() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("payload_too_large") }
        }

        assertThat(dsl.fetchCount(CIRCULATION_FINE)).isZero()
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isZero()
    }

    @Test
    fun `membership eligibility events are ordered replay safe and tamper evident`() {
        val memberId = MemberId(UUID.randomUUID())
        val eligible = eligibilityEvent(
            memberId = memberId,
            aggregateVersion = 0,
            status = MemberEligibilityStatus.ELIGIBLE,
        )

        val executions = runOneHundredConcurrently {
            applyMembershipEligibility.apply(eligible)
        }

        assertThat(executions.count { !it.replayed }).isOne()
        assertThat(executions.count { it.replayed }).isEqualTo(CONCURRENT_COMMANDS - 1)
        assertThat(executions.map { it.eligibility }.distinct()).hasSize(1)
        assertThat(dsl.fetchCount(CIRCULATION_MEMBER_ELIGIBILITY)).isOne()
        assertThat(dsl.fetchCount(CIRCULATION_CONSUMER_INBOX)).isOne()

        assertThatThrownBy {
            applyMembershipEligibility.apply(
                eligibilityEvent(
                    memberId = memberId,
                    aggregateVersion = 2,
                    status = MemberEligibilityStatus.SUSPENDED,
                    reasonCode = EligibilityReasonCode.parse("ACCOUNT_SUSPENDED"),
                ),
            )
        }.isInstanceOf(MembershipEventGapException::class.java)
        assertThat(dsl.fetchCount(CIRCULATION_CONSUMER_INBOX)).isOne()

        val suspended = eligibilityEvent(
            memberId = memberId,
            aggregateVersion = 1,
            status = MemberEligibilityStatus.SUSPENDED,
            reasonCode = EligibilityReasonCode.parse("ACCOUNT_SUSPENDED"),
        )
        val applied = applyMembershipEligibility.apply(suspended)
        val replay = applyMembershipEligibility.apply(suspended)
        assertThat(applied.disposition).isEqualTo(EligibilityEventDisposition.APPLIED)
        assertThat(applied.replayed).isFalse()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.eligibility.status).isEqualTo(MemberEligibilityStatus.SUSPENDED)

        assertThatThrownBy {
            applyMembershipEligibility.apply(suspended.copy(eventId = UUID.randomUUID()))
        }.isInstanceOf(MembershipEventConflictException::class.java)
        assertThat(dsl.fetchCount(CIRCULATION_CONSUMER_INBOX)).isEqualTo(2)

        val backfilledMember = MemberId(UUID.randomUUID())
        seedEligibility(
            memberId = backfilledMember,
            status = MemberEligibilityStatus.ELIGIBLE,
            sourceVersion = 3,
        )
        val stale = applyMembershipEligibility.apply(
            eligibilityEvent(
                memberId = backfilledMember,
                aggregateVersion = 2,
                status = MemberEligibilityStatus.INELIGIBLE,
                reasonCode = EligibilityReasonCode.parse("STALE_MEMBERSHIP_STATE"),
            ),
        )
        assertThat(stale.disposition).isEqualTo(EligibilityEventDisposition.STALE)
        assertThat(stale.eligibility.status).isEqualTo(MemberEligibilityStatus.ELIGIBLE)
        assertThat(stale.eligibility.sourceVersion).isEqualTo(3)

        assertThatThrownBy {
            dsl.update(CIRCULATION_CONSUMER_INBOX)
                .set(CIRCULATION_CONSUMER_INBOX.DISPOSITION, "STALE")
                .where(CIRCULATION_CONSUMER_INBOX.EVENT_ID.eq(eligible.eventId))
                .execute()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("immutable")
        assertThatThrownBy {
            dsl.deleteFrom(CIRCULATION_CONSUMER_INBOX)
                .where(CIRCULATION_CONSUMER_INBOX.EVENT_ID.eq(eligible.eventId))
                .execute()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("immutable")
        assertThatThrownBy {
            dsl.update(CIRCULATION_MEMBER_ELIGIBILITY)
                .set(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION, 7L)
                .where(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID.eq(memberId.value))
                .execute()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("invalid circulation eligibility projection transition")
        assertThatThrownBy {
            dsl.deleteFrom(CIRCULATION_MEMBER_ELIGIBILITY)
                .where(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID.eq(memberId.value))
                .execute()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("cannot be deleted")
    }

    @Test
    fun `loan decisions fail closed while suspended members can still return books`() {
        val missingMember = MemberId(UUID.randomUUID())
        val editionId = EditionId(UUID.randomUUID())
        assertThatThrownBy {
            requestLoan.request(
                RequestLoanCommand(
                    memberId = missingMember,
                    editionId = editionId,
                    idempotencyKey = IdempotencyKey.parse("missing-eligibility-${UUID.randomUUID()}"),
                    principal = selfPrincipal(missingMember, "missing-eligibility-member"),
                ),
            )
        }.isInstanceOf(MemberEligibilityUnavailableException::class.java)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isZero()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isZero()

        val ineligibleMember = MemberId(UUID.randomUUID())
        seedEligibility(
            memberId = ineligibleMember,
            status = MemberEligibilityStatus.INELIGIBLE,
            reasonCode = EligibilityReasonCode.parse("ACCOUNT_NOT_APPROVED"),
        )
        assertThatThrownBy {
            requestLoan.request(
                RequestLoanCommand(
                    memberId = ineligibleMember,
                    editionId = editionId,
                    idempotencyKey = IdempotencyKey.parse("ineligible-request-${UUID.randomUUID()}"),
                    principal = selfPrincipal(ineligibleMember, "ineligible-member"),
                ),
            )
        }.isInstanceOf(MemberNotEligibleException::class.java)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isZero()
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isZero()

        val pendingMember = MemberId(UUID.randomUUID())
        val pendingEdition = EditionId(UUID.randomUUID())
        val pendingCopyId = UUID.randomUUID()
        seedEligible(pendingMember)
        seedCopy(
            pendingCopyId,
            pendingEdition.value,
            UUID.randomUUID(),
            "SUSPEND-${UUID.randomUUID()}",
        )
        val pendingLoan = requestLoan.request(
            RequestLoanCommand(
                memberId = pendingMember,
                editionId = pendingEdition,
                idempotencyKey = IdempotencyKey.parse("pre-suspension-request-${UUID.randomUUID()}"),
                principal = selfPrincipal(pendingMember, "pre-suspension-member"),
            ),
        )
        applyMembershipEligibility.apply(
            eligibilityEvent(
                memberId = pendingMember,
                aggregateVersion = 1,
                status = MemberEligibilityStatus.SUSPENDED,
                reasonCode = EligibilityReasonCode.parse("ACCOUNT_SUSPENDED"),
            ),
        )
        assertThatThrownBy {
            approveLoan.approve(
                ApproveLoanCommand(
                    loanId = pendingLoan.result.loanId,
                    idempotencyKey = IdempotencyKey.parse("suspended-approve-${UUID.randomUUID()}"),
                    principal = administrativePrincipal("suspended-approve-staff"),
                ),
            )
        }.isInstanceOf(MemberNotEligibleException::class.java)
        assertThat(
            dsl.select(CIRCULATION_COPY.STATUS)
                .from(CIRCULATION_COPY)
                .where(CIRCULATION_COPY.ID.eq(pendingCopyId))
                .fetchSingle(CIRCULATION_COPY.STATUS),
        ).isEqualTo(CopyStatus.AVAILABLE.name)
        assertThat(
            dsl.select(CIRCULATION_LOAN.STATUS)
                .from(CIRCULATION_LOAN)
                .where(CIRCULATION_LOAN.ID.eq(pendingLoan.result.loanId.value))
                .fetchSingle(CIRCULATION_LOAN.STATUS),
        ).isEqualTo(LoanStatus.REQUESTED.name)

        val activeMember = MemberId(UUID.randomUUID())
        val activeLoanId = createActiveLoan(activeMember)
        applyMembershipEligibility.apply(
            eligibilityEvent(
                memberId = activeMember,
                aggregateVersion = 1,
                status = MemberEligibilityStatus.SUSPENDED,
                reasonCode = EligibilityReasonCode.parse("ACCOUNT_SUSPENDED"),
            ),
        )

        assertThatThrownBy {
            renewLoan.renew(
                RenewLoanCommand(
                    loanId = activeLoanId,
                    idempotencyKey = IdempotencyKey.parse("suspended-renew-${UUID.randomUUID()}"),
                    principal = selfPrincipal(activeMember, "suspended-renew-member"),
                ),
            )
        }.isInstanceOf(MemberNotEligibleException::class.java)

        val returned = returnLoan.returnLoan(
            ReturnLoanCommand(
                loanId = activeLoanId,
                idempotencyKey = IdempotencyKey.parse("suspended-return-${UUID.randomUUID()}"),
                principal = administrativePrincipal("suspended-return-staff"),
            ),
        )
        assertThat(returned.result.status).isEqualTo(LoanStatus.RETURNED)
    }

    @Test
    fun `policy and eligibility reads enforce exact scopes and member privacy`() {
        val memberId = MemberId(UUID.randomUUID())
        seedEligible(memberId)

        mockMvc.get(POLICY_PATH)
            .andExpect { status { isUnauthorized() } }
        mockMvc.get(POLICY_PATH) {
            with(jwtFor("wrong-policy-scope", ELIGIBILITY_READ_SCOPE, memberId.value))
        }.andExpect { status { isForbidden() } }
        mockMvc.get(POLICY_PATH) {
            with(jwtFor("policy-reader", POLICY_READ_SCOPE))
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.revision") {
                value(matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            }
            jsonPath("$.sequence") { value(0) }
            jsonPath("$.defaultLoanPeriod") { value("PT336H") }
            jsonPath("$.renewalPeriod") { value("PT336H") }
            jsonPath("$.maximumRenewals") { value(2) }
            jsonPath("$.fineCurrency") { value("MAD") }
            jsonPath("$.reservationHoldPeriod") { value("PT48H") }
            jsonPath("$.maximumActiveReservations") { value(10) }
            jsonPath("$.effectiveAt") { exists() }
        }

        val eligibilityPath = "$MEMBERS_PATH/${memberId.value}/eligibility"
        mockMvc.get(eligibilityPath) {
            with(jwtFor("wrong-eligibility-scope", POLICY_READ_SCOPE))
        }.andExpect { status { isForbidden() } }
        mockMvc.get(eligibilityPath) {
            with(jwtFor("self-eligibility-reader", ELIGIBILITY_READ_SCOPE, memberId.value))
        }.andExpect {
            status { isOk() }
            jsonPath("$.memberId") { value(memberId.value.toString()) }
            jsonPath("$.status") { value("ELIGIBLE") }
            jsonPath("$.reasonCode") { doesNotExist() }
            jsonPath("$.sourceVersion") { value(0) }
            jsonPath("$.sourceOccurredAt") { exists() }
        }
        mockMvc.get("$MEMBERS_PATH/${UUID.randomUUID()}/eligibility") {
            with(jwtFor("self-eligibility-reader", ELIGIBILITY_READ_SCOPE, memberId.value))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("member_eligibility_not_found") }
        }
        mockMvc.get(eligibilityPath) {
            with(jwtFor("staff-eligibility-reader", ELIGIBILITY_READ_ANY_SCOPE))
        }.andExpect {
            status { isOk() }
            jsonPath("$.memberId") { value(memberId.value.toString()) }
        }
    }

    @Test
    fun `reservation queue transfers a returned copy fairly and replays exact results`() {
        val editionId = EditionId(UUID.randomUUID())
        val firstMember = MemberId(UUID.randomUUID())
        val secondMember = MemberId(UUID.randomUUID())
        val copyId = UUID.randomUUID()
        seedEligible(firstMember)
        seedEligible(secondMember)
        seedCopy(copyId, editionId.value, UUID.randomUUID(), "R-${UUID.randomUUID()}")

        val firstCommand = PlaceReservationCommand(
            firstMember,
            editionId,
            IdempotencyKey.parse("place-first-${UUID.randomUUID()}"),
            selfPrincipal(firstMember, "reservation-first"),
        )
        val first = placeReservation.place(firstCommand)
        assertThat(first.result.status).isEqualTo(ReservationStatus.READY)
        assertThat(first.result.copyId?.value).isEqualTo(copyId)

        val secondCommand = PlaceReservationCommand(
            secondMember,
            editionId,
            IdempotencyKey.parse("place-second-${UUID.randomUUID()}"),
            selfPrincipal(secondMember, "reservation-second"),
        )
        val second = placeReservation.place(secondCommand)
        assertThat(second.result.status).isEqualTo(ReservationStatus.WAITING)

        fulfillReservation.fulfill(
            FulfillReservationCommand(
                first.result.reservationId,
                IdempotencyKey.parse("fulfill-first-${UUID.randomUUID()}"),
                administrativePrincipal("reservation-desk"),
            ),
        )
        val firstLoanId = LoanId(
            requireNotNull(
                dsl.select(CIRCULATION_LOAN.ID)
                    .from(CIRCULATION_LOAN)
                    .where(
                        CIRCULATION_LOAN.MEMBER_ID.eq(firstMember.value)
                            .and(CIRCULATION_LOAN.STATUS.eq("ACTIVE")),
                    )
                    .fetchOne(CIRCULATION_LOAN.ID),
            ),
        )
        returnLoan.returnLoan(
            ReturnLoanCommand(
                firstLoanId,
                IdempotencyKey.parse("return-reserved-${UUID.randomUUID()}"),
                administrativePrincipal("reservation-desk"),
            ),
        )

        val promoted = requireNotNull(
            dsl.selectFrom(CIRCULATION_RESERVATION)
                .where(CIRCULATION_RESERVATION.ID.eq(second.result.reservationId.value))
                .fetchOne(),
        )
        assertThat(promoted.status).isEqualTo("READY")
        assertThat(promoted.copyId).isEqualTo(copyId)
        assertThat(
            dsl.select(CIRCULATION_COPY.STATUS)
                .from(CIRCULATION_COPY)
                .where(CIRCULATION_COPY.ID.eq(copyId))
                .fetchOne(CIRCULATION_COPY.STATUS),
        ).isEqualTo("RESERVED")

        val replay = placeReservation.place(secondCommand)
        assertThat(replay.replayed).isTrue()
        assertThat(replay.result.status).isEqualTo(ReservationStatus.WAITING)
        assertThat(replay.result.version).isZero()

        fulfillReservation.fulfill(
            FulfillReservationCommand(
                second.result.reservationId,
                IdempotencyKey.parse("fulfill-second-${UUID.randomUUID()}"),
                administrativePrincipal("reservation-desk"),
            ),
        )
        assertThat(
            dsl.select(CIRCULATION_COPY.STATUS)
                .from(CIRCULATION_COPY)
                .where(CIRCULATION_COPY.ID.eq(copyId))
                .fetchOne(CIRCULATION_COPY.STATUS),
        ).isEqualTo("ON_LOAN")
    }

    @Test
    fun `reservation fulfillment and expiry reject unrelated member principals`() {
        val memberId = MemberId(UUID.randomUUID())
        val unrelatedMemberId = MemberId(UUID.randomUUID())
        val editionId = EditionId(UUID.randomUUID())
        val copyId = UUID.randomUUID()
        seedEligible(memberId)
        seedCopy(copyId, editionId.value, UUID.randomUUID(), "AUTH-${UUID.randomUUID()}")
        val reservation = placeReservation.place(
            PlaceReservationCommand(
                memberId,
                editionId,
                IdempotencyKey.parse("place-auth-${UUID.randomUUID()}"),
                selfPrincipal(memberId, "reservation-owner"),
            ),
        )
        val unrelated = selfPrincipal(unrelatedMemberId, "unrelated-member")

        assertThatThrownBy {
            fulfillReservation.fulfill(
                FulfillReservationCommand(
                    reservation.result.reservationId,
                    IdempotencyKey.parse("fulfill-auth-${UUID.randomUUID()}"),
                    unrelated,
                ),
            )
        }.isInstanceOf(ReservationNotFoundException::class.java)
        assertThatThrownBy {
            expireReservation.expire(
                ExpireReservationCommand(
                    reservation.result.reservationId,
                    IdempotencyKey.parse("expire-auth-${UUID.randomUUID()}"),
                    unrelated,
                ),
            )
        }.isInstanceOf(ReservationNotFoundException::class.java)

        assertThat(
            dsl.select(CIRCULATION_RESERVATION.STATUS)
                .from(CIRCULATION_RESERVATION)
                .where(CIRCULATION_RESERVATION.ID.eq(reservation.result.reservationId.value))
                .fetchSingle(CIRCULATION_RESERVATION.STATUS),
        ).isEqualTo(ReservationStatus.READY.name)
        assertThat(dsl.fetchCount(CIRCULATION_LOAN)).isZero()
    }

    @Test
    fun `policy updates are immutable compare and set and replay safe`() {
        val current = getCirculationPolicy.get()
        val key = IdempotencyKey.parse("policy-update-${UUID.randomUUID()}")
        val command = UpdateCirculationPolicyCommand(
            expectedRevision = current.revision,
            values = UpdateCirculationPolicyValues(
                defaultLoanPeriod = Duration.ofDays(21),
                renewalPeriod = Duration.ofDays(7),
                maximumRenewals = 3,
                fineCurrency = "MAD",
                reservationHoldPeriod = Duration.ofHours(36),
                maximumActiveReservations = 8,
            ),
            idempotencyKey = key,
            principal = administrativePrincipal("policy-administrator"),
        )

        val updated = updateCirculationPolicy.update(command)
        val replay = updateCirculationPolicy.update(command)
        assertThat(updated.replayed).isFalse()
        assertThat(updated.result.sequence).isEqualTo(current.sequence + 1)
        assertThat(updated.result.defaultLoanPeriod).isEqualTo(Duration.ofDays(21))
        assertThat(replay.replayed).isTrue()
        assertThat(replay.result).isEqualTo(updated.result)
        assertThat(getCirculationPolicy.get()).isEqualTo(updated.result)

        assertThatThrownBy {
            updateCirculationPolicy.update(
                command.copy(
                    idempotencyKey = IdempotencyKey.parse("stale-policy-${UUID.randomUUID()}"),
                ),
            )
        }.isInstanceOf(PolicyRevisionConflictException::class.java)
        assertThat(
            dsl.fetchCount(
                dsl.selectFrom("circulation_policy_revision"),
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `distributed rate limit serializes concurrent requests exactly`() {
        val now = Instant.now()
        val decisions = runConcurrently(100) {
            rateLimitStore.consume(
                principalFingerprint = "a".repeat(64),
                bucketKey = "sensitive",
                limit = 30,
                window = Duration.ofMinutes(1),
                now = now,
            )
        }

        assertThat(decisions).allMatch(Result<*>::isSuccess)
        assertThat(decisions.map { it.getOrThrow() }.count { it.allowed }).isEqualTo(30)
        assertThat(
            dsl.fetchOne(
                "SELECT request_count FROM circulation_rate_limit_bucket " +
                    "WHERE principal_fingerprint = ? AND bucket_key = ?",
                "a".repeat(64),
                "sensitive",
            )?.get("request_count", Int::class.javaObjectType),
        ).isEqualTo(31)
    }

    @Test
    fun `new inventory promotes the oldest waiting reservation atomically`() {
        val memberId = MemberId(UUID.randomUUID())
        val editionId = EditionId(UUID.randomUUID())
        seedEligible(memberId)
        val waiting = placeReservation.place(
            PlaceReservationCommand(
                memberId,
                editionId,
                IdempotencyKey.parse("waiting-before-copy-${UUID.randomUUID()}"),
                selfPrincipal(memberId, "waiting-before-copy"),
            ),
        )
        assertThat(waiting.result.status).isEqualTo(ReservationStatus.WAITING)

        val copyId = CopyId(UUID.randomUUID())
        val registerCommand = RegisterCopyCommand(
            copyId,
            editionId,
            BranchId(UUID.randomUUID()),
            CopyBarcode.parse("RP-${UUID.randomUUID()}"),
            ShelfLocation.parse("HOLD-DESK"),
            InventoryReason.parse("New copy available for queued hold"),
            IdempotencyKey.parse("register-for-hold-${UUID.randomUUID()}"),
            administrativePrincipal("inventory-for-hold"),
        )
        val registered = registerCopy.register(registerCommand)
        val replay = registerCopy.register(registerCommand)

        assertThat(registered.result.status).isEqualTo(CopyStatus.RESERVED)
        assertThat(registered.result.version).isOne()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.result).isEqualTo(registered.result)

        assertThat(
            dsl.select(CIRCULATION_RESERVATION.STATUS)
                .from(CIRCULATION_RESERVATION)
                .where(CIRCULATION_RESERVATION.ID.eq(waiting.result.reservationId.value))
                .fetchOne(CIRCULATION_RESERVATION.STATUS),
        ).isEqualTo("READY")
        assertThat(
            dsl.select(CIRCULATION_COPY.STATUS)
                .from(CIRCULATION_COPY)
                .where(CIRCULATION_COPY.ID.eq(copyId.value))
                .fetchOne(CIRCULATION_COPY.STATUS),
        ).isEqualTo("RESERVED")
        val audit = dsl.selectFrom(CIRCULATION_INVENTORY_AUDIT_ENTRY)
            .where(CIRCULATION_INVENTORY_AUDIT_ENTRY.COPY_ID.eq(copyId.value))
            .fetchSingle()
        assertThat(audit.copyStatus).isEqualTo(CopyStatus.RESERVED.name)
        assertThat(audit.copyVersion).isOne()
        val copyEvent = dsl.selectFrom(OUTBOX_EVENT)
            .where(
                OUTBOX_EVENT.AGGREGATE_TYPE.eq("copy")
                    .and(OUTBOX_EVENT.AGGREGATE_ID.eq(copyId.value)),
            )
            .fetchSingle()
        assertThat(copyEvent.aggregateVersion).isOne()
        assertThat(copyEvent.payload?.data()).contains("\"status\": \"RESERVED\"")
    }

    @Test
    fun `automatic expiry releases a held copy and emits a terminal event`() {
        val memberId = MemberId(UUID.randomUUID())
        val editionId = EditionId(UUID.randomUUID())
        val copyId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        seedEligible(memberId)
        seedCopy(copyId, editionId.value, UUID.randomUUID(), "EX-${UUID.randomUUID()}")
        dsl.update(CIRCULATION_COPY)
            .set(CIRCULATION_COPY.STATUS, "RESERVED")
            .set(CIRCULATION_COPY.VERSION, 1L)
            .where(CIRCULATION_COPY.ID.eq(copyId))
            .execute()
        dsl.insertInto(CIRCULATION_RESERVATION)
            .set(CIRCULATION_RESERVATION.ID, reservationId)
            .set(CIRCULATION_RESERVATION.MEMBER_ID, memberId.value)
            .set(CIRCULATION_RESERVATION.EDITION_ID, editionId.value)
            .set(CIRCULATION_RESERVATION.COPY_ID, copyId)
            .set(CIRCULATION_RESERVATION.STATUS, "READY")
            .set(CIRCULATION_RESERVATION.PLACED_AT, now.minusHours(3))
            .set(CIRCULATION_RESERVATION.READY_AT, now.minusHours(2))
            .set(CIRCULATION_RESERVATION.EXPIRES_AT, now.minusHours(1))
            .set(CIRCULATION_RESERVATION.VERSION, 1L)
            .set(CIRCULATION_RESERVATION.CREATED_AT, now.minusHours(3))
            .set(CIRCULATION_RESERVATION.UPDATED_AT, now.minusHours(2))
            .execute()

        assertThat(reservationExpiryService.expireIfDue(com.mundiapolis.library.circulation.domain.model.ReservationId(reservationId)))
            .isTrue()
        assertThat(
            dsl.select(CIRCULATION_RESERVATION.STATUS)
                .from(CIRCULATION_RESERVATION)
                .where(CIRCULATION_RESERVATION.ID.eq(reservationId))
                .fetchOne(CIRCULATION_RESERVATION.STATUS),
        ).isEqualTo("EXPIRED")
        assertThat(
            dsl.select(CIRCULATION_COPY.STATUS)
                .from(CIRCULATION_COPY)
                .where(CIRCULATION_COPY.ID.eq(copyId))
                .fetchOne(CIRCULATION_COPY.STATUS),
        ).isEqualTo("AVAILABLE")
        assertThat(
            dsl.fetchCount(
                OUTBOX_EVENT,
                OUTBOX_EVENT.AGGREGATE_ID.eq(reservationId)
                    .and(OUTBOX_EVENT.EVENT_TYPE.eq("circulation.reservation.expired")),
            ),
        ).isEqualTo(1)
    }

    private fun createActiveLoan(memberId: MemberId): LoanId {
        val editionId = EditionId(UUID.randomUUID())
        seedCopy(UUID.randomUUID(), editionId.value, UUID.randomUUID(), "C-${UUID.randomUUID()}")
        seedEligible(memberId)
        val requested = requestLoan.request(
            RequestLoanCommand(
                memberId = memberId,
                editionId = editionId,
                idempotencyKey = IdempotencyKey.parse("request-phase2-${UUID.randomUUID()}"),
                principal = selfPrincipal(memberId, "phase2-member-${memberId.value}"),
            ),
        )
        return approveLoan.approve(
            ApproveLoanCommand(
                loanId = requested.result.loanId,
                idempotencyKey = IdempotencyKey.parse("approve-phase2-${UUID.randomUUID()}"),
                principal = administrativePrincipal("phase2-approver"),
            ),
        ).result.loanId
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

    private fun seedEligible(memberId: MemberId) {
        seedEligibility(
            memberId = memberId,
            status = MemberEligibilityStatus.ELIGIBLE,
        )
    }

    private fun seedEligibility(
        memberId: MemberId,
        status: MemberEligibilityStatus,
        reasonCode: EligibilityReasonCode? = null,
        sourceVersion: Long = 0,
    ) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(CIRCULATION_MEMBER_ELIGIBILITY)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID, memberId.value)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.STATUS, status.name)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.REASON_CODE, reasonCode?.value)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION, sourceVersion)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_OCCURRED_AT, now)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.CREATED_AT, now)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    private fun eligibilityEvent(
        memberId: MemberId,
        aggregateVersion: Long,
        status: MemberEligibilityStatus,
        reasonCode: EligibilityReasonCode? = null,
        eventId: UUID = UUID.randomUUID(),
    ): MembershipEligibilityEvent = MembershipEligibilityEvent(
        eventId = eventId,
        eventType = MembershipEligibilityEvent.EVENT_TYPE,
        eventVersion = MembershipEligibilityEvent.EVENT_VERSION,
        memberId = memberId,
        aggregateVersion = aggregateVersion,
        status = status,
        reasonCode = reasonCode,
        occurredAt = Instant.now(),
    )

    private fun jwtFor(
        subject: String,
        authority: String,
        membershipClaim: Any? = null,
    ): RequestPostProcessor = jwt()
        .jwt { builder ->
            builder
                .subject(subject)
                .claim("iss", TEST_ISSUER)
                .claim("client_id", TEST_CLIENT_ID)
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

    private fun administrativePrincipal(subject: String): CommandPrincipal =
        CommandPrincipal(
            idempotencyOwner = idempotencyOwner(subject),
            membershipId = null,
            canActOnBehalf = true,
        )

    private fun idempotencyOwner(subject: String): IdempotencyOwner =
        IdempotencyOwner.fromIdentity(
            issuer = TEST_ISSUER,
            subject = subject,
            authorizedParty = null,
            clientId = TEST_CLIENT_ID,
        )

    private fun <T> runOneHundredConcurrently(action: () -> T): List<T> {
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..CONCURRENT_COMMANDS).map {
                executor.submit<T> {
                    start.await()
                    action()
                }
            }
            start.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }
        }
    }

    private fun <T> runConcurrently(count: Int, action: (Int) -> T): List<Result<T>> {
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..count).map { index ->
                executor.submit<Result<T>> {
                    start.await()
                    runCatching { action(index) }
                }
            }
            start.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }
        }
    }

    private companion object {
        val POLICY_SEED_REVISION: UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001",
        )
        const val LOANS_PATH = "/api/v1/circulation/loans"
        const val FINES_PATH = "/api/v1/circulation/fines"
        const val COPIES_PATH = "/api/v1/circulation/copies"
        const val POLICY_PATH = "/api/v1/circulation/policy"
        const val MEMBERS_PATH = "/api/v1/circulation/members"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val RENEW_SCOPE = "SCOPE_circulation.loan.renew"
        const val RENEW_ON_BEHALF_SCOPE = "SCOPE_circulation.loan.renew.on-behalf"
        const val ASSESS_FINE_SCOPE = "SCOPE_circulation.fine.assess"
        const val RECORD_PAYMENT_SCOPE = "SCOPE_circulation.fine.payment.record"
        const val POLICY_READ_SCOPE = "SCOPE_circulation.policy.read"
        const val ELIGIBILITY_READ_SCOPE = "SCOPE_circulation.eligibility.read"
        const val ELIGIBILITY_READ_ANY_SCOPE = "SCOPE_circulation.eligibility.read.any"
        const val REGISTER_INVENTORY_SCOPE = "SCOPE_circulation.inventory.register"
        const val CONDITION_INVENTORY_SCOPE =
            "SCOPE_circulation.inventory.condition.update"
        const val CONCURRENT_COMMANDS = 100
        const val TEST_ISSUER = "https://issuer.example.test"
        const val TEST_CLIENT_ID = "circulation-phase2-integration-test"

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
            registry.add("app.rate-limit.enabled") { "false" }
            registry.add("app.reservation-expiry.enabled") { "false" }
        }
    }
}
