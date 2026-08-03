package com.mundiapolis.library.circulation

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_COPY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE_LEDGER_ENTRY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_INVENTORY_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_INVENTORY_AUDIT_ENTRY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.BrokerPublishAcknowledgement
import com.mundiapolis.library.circulation.application.model.DuplicatePaymentReferenceException
import com.mundiapolis.library.circulation.application.model.FineBalanceConflictException
import com.mundiapolis.library.circulation.application.model.FineNarrative
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.LoanOverdueException
import com.mundiapolis.library.circulation.application.model.PaymentReference
import com.mundiapolis.library.circulation.application.model.RenewalLimitReachedException
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineUseCase
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
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.application.port.outbound.OutboxDeliveryStore
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.BranchId
import com.mundiapolis.library.circulation.domain.model.CopyBarcode
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.CopyStatus
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryType
import com.mundiapolis.library.circulation.domain.model.FineStatus
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.InventoryReason
import com.mundiapolis.library.circulation.domain.model.ShelfLocation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
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
import javax.sql.DataSource

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "app.security.jwt.issuer=https://issuer.example.test",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.audience=circulation-api",
        "app.circulation.default-loan-period=P14D",
        "app.circulation.renewal-period=P14D",
        "app.circulation.maximum-renewals=2",
        "app.circulation.fine-currency=MAD",
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

    @BeforeEach
    fun cleanCommandData() {
        dsl.execute("ALTER TABLE circulation_fine_ledger_entry DISABLE TRIGGER USER")
        dsl.execute("ALTER TABLE circulation_inventory_audit_entry DISABLE TRIGGER USER")
        try {
            dsl.execute(
                """
                TRUNCATE TABLE
                    circulation_fine_ledger_entry,
                    circulation_fine,
                    circulation_inventory_audit_entry,
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
                """{"amountMinor":100,"externalReference":"PAY-${UUID.randomUUID()}"}"""
            header(IDEMPOTENCY_HEADER, "payment-wrong-scope-${UUID.randomUUID()}")
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post("$FINES_PATH/$fineId/adjustments") {
            with(jwtFor("payment-recorder", RECORD_PAYMENT_SCOPE))
            contentType = MediaType.APPLICATION_JSON
            content = """{"deltaMinor":-100,"reason":"Approved waiver"}"""
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
                    amountMinor = 3_001,
                    externalReference = PaymentReference.parse("PAY-${UUID.randomUUID()}"),
                    idempotencyKey = IdempotencyKey.parse("overpayment-${UUID.randomUUID()}"),
                    principal = staff,
                ),
            )
        }.isInstanceOf(FineBalanceConflictException::class.java)

        val adjusted = adjustFine.adjust(
            AdjustFineCommand(
                fineId = assessed.result.fineId,
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

    private fun createActiveLoan(memberId: MemberId): LoanId {
        val editionId = EditionId(UUID.randomUUID())
        seedCopy(UUID.randomUUID(), editionId.value, UUID.randomUUID(), "C-${UUID.randomUUID()}")
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
            canActOnBehalf = false,
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
        const val LOANS_PATH = "/api/v1/circulation/loans"
        const val FINES_PATH = "/api/v1/circulation/fines"
        const val COPIES_PATH = "/api/v1/circulation/copies"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val RENEW_SCOPE = "SCOPE_circulation.loan.renew"
        const val RENEW_ON_BEHALF_SCOPE = "SCOPE_circulation.loan.renew.on-behalf"
        const val ASSESS_FINE_SCOPE = "SCOPE_circulation.fine.assess"
        const val RECORD_PAYMENT_SCOPE = "SCOPE_circulation.fine.payment.record"
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
        }
    }
}
