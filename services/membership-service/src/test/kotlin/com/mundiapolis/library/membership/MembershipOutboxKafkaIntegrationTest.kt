package com.mundiapolis.library.membership

import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_AUDIT_ENTRY
import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_MEMBER
import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_OUTBOX_EVENT
import com.mundiapolis.library.membership.config.MembershipOutboxConfiguration
import com.mundiapolis.library.membership.config.MembershipOutboxProperties
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityChanged
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityStatus
import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.ChangeAccountStatusCommand
import com.mundiapolis.library.membership.service.MembershipCommandService
import com.mundiapolis.library.membership.service.MembershipOutboxDeliveryService
import com.mundiapolis.library.membership.service.MembershipOutboxStore
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers
@SpringBootTest(
    properties = [
        "app.security.jwt.issuer=https://issuer.example.test",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.audience=membership-api",
    ],
)
class MembershipOutboxKafkaIntegrationTest {
    @Autowired
    private lateinit var commandService: MembershipCommandService

    @Autowired
    private lateinit var outboxStore: MembershipOutboxStore

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `transactional membership command reaches Kafka through the production outbox`() {
        createTopic()
        insertMember(TARGET_MEMBER_ID, status = AccountStatus.PENDING, role = "USER")
        insertMember(ACTOR_MEMBER_ID, status = AccountStatus.APPROVED, role = "ADMIN")
        val command = ChangeAccountStatusCommand(
            memberId = TARGET_MEMBER_ID,
            expectedVersion = 0,
            status = AccountStatus.APPROVED,
            reason = "Identity evidence was verified",
            idempotencyKey = "membership-kafka-pipeline-0001",
            ownerFingerprint = "a".repeat(64),
            actorMemberId = ACTOR_MEMBER_ID,
        )

        val execution = commandService.changeAccountStatus(command)

        assertThat(execution.replayed).isFalse()
        assertThat(execution.result.aggregateVersion).isEqualTo(1)
        val member = dsl.selectFrom(MEMBERSHIP_MEMBER)
            .where(MEMBERSHIP_MEMBER.MEMBER_ID.eq(TARGET_MEMBER_ID))
            .fetchSingle()
        assertThat(member.accountStatus).isEqualTo(AccountStatus.APPROVED.name)
        assertThat(member.aggregateVersion).isEqualTo(1)
        assertThat(dsl.fetchCount(MEMBERSHIP_AUDIT_ENTRY)).isOne()
        assertThat(dsl.fetchCount(MEMBERSHIP_OUTBOX_EVENT)).isOne()

        val properties = outboxProperties()
        val configuration = MembershipOutboxConfiguration()
        configuration.membershipKafkaProducer(properties).use { producer ->
            val delivery = MembershipOutboxDeliveryService(
                store = outboxStore,
                encoder = configuration.membershipEventEncoder(objectMapper, properties),
                publisher = configuration.membershipBrokerPublisher(producer, properties),
                clock = clock,
                properties = properties,
            )
            consumer().use { consumer ->
                consumer.subscribe(listOf(TOPIC))

                val cycle = delivery.deliverBatch()
                val record = awaitRecord(consumer)
                val message = MemberEligibilityChanged.parseFrom(record.value())

                assertThat(cycle.claimed).isOne()
                assertThat(cycle.published).isOne()
                assertThat(cycle.retryScheduled).isZero()
                assertThat(cycle.blocked).isZero()
                assertThat(record.key()).isEqualTo(TARGET_MEMBER_ID.toString())
                assertThat(record.header("content-type")).isEqualTo("application/x-protobuf")
                assertThat(record.header("event-type"))
                    .isEqualTo("membership.member.eligibility-changed")
                assertThat(record.header("schema-subject")).isEqualTo(SCHEMA_SUBJECT)
                assertThat(message.memberId).isEqualTo(TARGET_MEMBER_ID.toString())
                assertThat(message.aggregateVersion).isEqualTo(1)
                assertThat(message.status)
                    .isEqualTo(MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE)
                assertThat(message.hasReasonCode()).isFalse()

                val outbox = dsl.selectFrom(MEMBERSHIP_OUTBOX_EVENT).fetchSingle()
                assertThat(outbox.publishedAt).isNotNull()
                assertThat(outbox.brokerTopic).isEqualTo(TOPIC)
                assertThat(outbox.brokerPartition).isEqualTo(record.partition())
                assertThat(outbox.brokerOffset).isEqualTo(record.offset())
                assertThat(outbox.payload.data())
                    .doesNotContain("@example.test", "Pipeline Target")

                val replay = commandService.changeAccountStatus(command)
                assertThat(replay.replayed).isTrue()
                assertThat(delivery.deliverBatch().claimed).isZero()
                assertThat(dsl.fetchCount(MEMBERSHIP_OUTBOX_EVENT)).isOne()
            }
        }
    }

    private fun createTopic() {
        Admin.create(
            mapOf(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG to 5_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG to 10_000,
            ),
        ).use { admin ->
            admin.createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort())))
                .all()
                .get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)
        }
    }

    private fun consumer(): KafkaConsumer<String, ByteArray> = KafkaConsumer(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "membership-outbox-kafka-integration",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
        ),
    )

    private fun awaitRecord(
        consumer: KafkaConsumer<String, ByteArray>,
    ): ConsumerRecord<String, ByteArray> {
        val deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).firstOrNull()?.let { return it }
        }
        throw AssertionError("Membership outbox event was not delivered through Kafka")
    }

    private fun insertMember(memberId: UUID, status: AccountStatus, role: String) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl.insertInto(MEMBERSHIP_MEMBER)
            .set(MEMBERSHIP_MEMBER.MEMBER_ID, memberId)
            .set(MEMBERSHIP_MEMBER.EMAIL, "member-${memberId.toString().take(8)}@example.test")
            .set(MEMBERSHIP_MEMBER.FULL_NAME, "Pipeline Target")
            .set(MEMBERSHIP_MEMBER.UNIVERSITY_ID, memberId.hashCode().and(Int.MAX_VALUE) + 1)
            .set(MEMBERSHIP_MEMBER.ACCOUNT_STATUS, status.name)
            .set(MEMBERSHIP_MEMBER.MEMBERSHIP_ROLE, role)
            .set(MEMBERSHIP_MEMBER.MAX_ACTIVE_LOANS, 5)
            .set(MEMBERSHIP_MEMBER.CURRENT_ACTIVE_LOANS, 0)
            .set(MEMBERSHIP_MEMBER.HAS_UNPAID_OVERDUE_FINES, false)
            .set(MEMBERSHIP_MEMBER.CREATED_AT, now)
            .set(MEMBERSHIP_MEMBER.UPDATED_AT, now)
            .execute()
    }

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String =
        String(requireNotNull(headers().lastHeader(name)).value(), StandardCharsets.UTF_8)

    private fun outboxProperties(): MembershipOutboxProperties = MembershipOutboxProperties(
        enabled = true,
        instanceId = "membership-outbox-kafka-integration",
        topic = TOPIC,
        schemaSubject = SCHEMA_SUBJECT,
        pollInterval = Duration.ofMillis(100),
        leaseDuration = Duration.ofMinutes(1),
        batchSize = 1,
        maximumAttempts = 3,
        retryBaseDelay = Duration.ofMillis(100),
        retryMaximumDelay = Duration.ofSeconds(1),
        publishedRetention = Duration.ofDays(30),
        cleanupInterval = Duration.ofHours(1),
        cleanupBatchSize = 100,
        maximumEventBytes = 65_536,
        maximumPendingAge = Duration.ofMinutes(5),
        kafka = MembershipOutboxProperties.KafkaProperties(
            bootstrapServers = listOf(kafka.bootstrapServers),
            securityProtocol = "PLAINTEXT",
            allowInsecureTransport = true,
            saslMechanism = null,
            saslJaasConfig = null,
            truststoreLocation = null,
            truststorePassword = null,
            keystoreLocation = null,
            keystorePassword = null,
            keyPassword = null,
            deliveryTimeout = Duration.ofSeconds(10),
            requestTimeout = Duration.ofSeconds(5),
            maximumBlock = Duration.ofSeconds(5),
        ),
    )

    private companion object {
        const val TOPIC = "mundia.membership.events.pipeline-test.v1"
        const val SCHEMA_SUBJECT = "mundia.membership.v1.MemberEligibilityChanged"
        val TARGET_MEMBER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val ACTOR_MEMBER_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val OPERATION_TIMEOUT: Duration = Duration.ofSeconds(20)

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("membership_outbox_kafka")
            .withUsername("membership")
            .withPassword("integration-test-only")

        @Container
        @JvmStatic
        val kafka = KafkaContainer("apache/kafka-native:4.2.0")
            .withStartupAttempts(3)
            .withStartupTimeout(Duration.ofMinutes(2))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
