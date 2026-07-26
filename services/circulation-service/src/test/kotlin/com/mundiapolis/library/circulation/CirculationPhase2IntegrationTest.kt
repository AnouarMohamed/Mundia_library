package com.mundiapolis.library.circulation

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_COPY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_FINE_LEDGER_ENTRY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_IDEMPOTENCY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_LOAN
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.DuplicatePaymentReferenceException
import com.mundiapolis.library.circulation.application.model.FineBalanceConflictException
import com.mundiapolis.library.circulation.application.model.FineNarrative
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.IdempotencyOwner
import com.mundiapolis.library.circulation.application.model.LoanOverdueException
import com.mundiapolis.library.circulation.application.model.PaymentReference
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AdjustFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.ApproveLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineCommand
import com.mundiapolis.library.circulation.application.port.inbound.AssessFineUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentCommand
import com.mundiapolis.library.circulation.application.port.inbound.RecordFinePaymentUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RenewLoanUseCase
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanCommand
import com.mundiapolis.library.circulation.application.port.inbound.RequestLoanUseCase
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.FineLedgerEntryType
import com.mundiapolis.library.circulation.domain.model.FineStatus
import com.mundiapolis.library.circulation.domain.model.LoanId
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
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
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun cleanCommandData() {
        dsl.execute("ALTER TABLE circulation_fine_ledger_entry DISABLE TRIGGER USER")
        try {
            dsl.execute(
                """
                TRUNCATE TABLE
                    circulation_fine_ledger_entry,
                    circulation_fine,
                    outbox_event,
                    circulation_idempotency,
                    circulation_loan,
                    circulation_copy
                """.trimIndent(),
            )
        } finally {
            dsl.execute("ALTER TABLE circulation_fine_ledger_entry ENABLE TRIGGER USER")
        }
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
            status { isForbidden() }
            jsonPath("$.code") { value("member_access_denied") }
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

        mockMvc.post("$LOANS_PATH/${active.value}/renew") {
            with(jwtFor("renew-staff", RENEW_ON_BEHALF_SCOPE))
            header(IDEMPOTENCY_HEADER, "renew-staff-${UUID.randomUUID()}")
        }.andExpect {
            status { isOk() }
            header { string(IDEMPOTENCY_REPLAYED_HEADER, "false") }
            jsonPath("$.renewalCount") { value(2) }
            jsonPath("$.version") { value(3) }
        }

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
    fun `V4 upgrades completed and pending V3 idempotency records on PostgreSQL 18`() {
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
                        assertThat(result.getInt("renewal_count")).isZero()
                        assertThat(result.wasNull()).isFalse()
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
                amountMinor = 5_000,
                reason = FineNarrative.parse("Overdue return"),
                idempotencyKey = IdempotencyKey.parse("assess-overdraw-${UUID.randomUUID()}"),
                principal = staff,
            ),
        )

        val payments = runConcurrently(2) { index ->
            recordFinePayment.recordPayment(
                RecordFinePaymentCommand(
                    fineId = assessed.result.fineId,
                    amountMinor = 3_000,
                    externalReference = PaymentReference.parse("PAY-$index-${UUID.randomUUID()}"),
                    idempotencyKey =
                        IdempotencyKey.parse("concurrent-payment-$index-${UUID.randomUUID()}"),
                    principal = staff,
                ),
            )
        }

        assertThat(payments.count { it.isSuccess }).isOne()
        assertThat(payments.count { it.exceptionOrNull() is FineBalanceConflictException }).isOne()
        val fine = dsl.selectFrom(CIRCULATION_FINE)
            .where(CIRCULATION_FINE.ID.eq(assessed.result.fineId.value))
            .fetchSingle()
        assertThat(fine.balanceMinor).isEqualTo(2_000)
        assertThat(fine.version).isOne()
        assertThat(
            dsl.selectFrom(CIRCULATION_FINE_LEDGER_ENTRY)
                .where(
                    CIRCULATION_FINE_LEDGER_ENTRY.FINE_ID.eq(assessed.result.fineId.value),
                )
                .fetch(CIRCULATION_FINE_LEDGER_ENTRY.DELTA_MINOR),
        ).containsExactlyInAnyOrder(5_000, -3_000)
        assertThat(
            dsl.selectFrom(OUTBOX_EVENT)
                .where(OUTBOX_EVENT.AGGREGATE_TYPE.eq("fine"))
                .fetch(),
        ).hasSize(2)
        assertThat(dsl.fetchCount(CIRCULATION_IDEMPOTENCY)).isEqualTo(4)
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
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val RENEW_SCOPE = "SCOPE_circulation.loan.renew"
        const val RENEW_ON_BEHALF_SCOPE = "SCOPE_circulation.loan.renew.on-behalf"
        const val ASSESS_FINE_SCOPE = "SCOPE_circulation.fine.assess"
        const val RECORD_PAYMENT_SCOPE = "SCOPE_circulation.fine.payment.record"
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
