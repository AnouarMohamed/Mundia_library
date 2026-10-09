package com.mundiapolis.library.circulation.adapter.`in`.events

import com.google.protobuf.Timestamp
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_CONSUMER_INBOX
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_MEMBER_ELIGIBILITY
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapCommand
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapItem
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityEvent
import com.mundiapolis.library.circulation.application.port.inbound.ApplyMembershipEligibilityEventUseCase
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.service.MembershipEligibilityBootstrapService
import com.mundiapolis.library.circulation.config.MembershipEligibilityConsumerConfiguration
import com.mundiapolis.library.circulation.config.MembershipEligibilityConsumerProperties
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityChanged
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityStatus as ContractEligibilityStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ConfigEntry
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers
@SpringBootTest(
    properties = [
        "app.security.jwt.issuer=https://issuer.example.test",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.audience=circulation-api",
        "app.rate-limit.enabled=false",
        "app.reservation-expiry.enabled=false",
    ],
)
class MembershipEligibilityKafkaBrokerReplayIntegrationTest {
    @Autowired
    private lateinit var bootstrapService: MembershipEligibilityBootstrapService

    @Autowired
    private lateinit var applyEligibilityEvent: ApplyMembershipEligibilityEventUseCase

    @Autowired
    private lateinit var timeProvider: TimeProvider

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `real broker offset reset replays the exact cutover boundary`() {
        val properties = consumerProperties()
        val partition = TopicPartition(TOPIC, 0)
        val memberId = MemberId(UUID.randomUUID())
        val snapshotTime = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS)

        Admin.create(adminProperties()).use { admin ->
            createAndVerifyTopic(admin)
            bootstrap(memberId, snapshotTime)

            producer().use { producer ->
                val versionEight = producer.send(
                    eligibilityRecord(
                        memberId = memberId,
                        aggregateVersion = 8,
                        status = ContractEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_SUSPENDED,
                        reasonCode = "ACCOUNT_SUSPENDED",
                        occurredAt = snapshotTime.plusSeconds(1),
                    ),
                ).get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)
                val versionNine = producer.send(
                    eligibilityRecord(
                        memberId = memberId,
                        aggregateVersion = 9,
                        status = ContractEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE,
                        occurredAt = snapshotTime.plusSeconds(2),
                    ),
                ).get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)
                assertThat(versionEight.offset()).isZero()
                assertThat(versionNine.offset()).isEqualTo(1)
            }

            seedGroupOffset(properties, partition, 1)
            awaitCondition { groupOffset(admin, partition) == 1L }

            val gapConsumer = consumer(properties)
            gapConsumer.start()
            awaitCondition {
                gapConsumer.healthSnapshot(Instant.now()).failure ==
                    MembershipConsumerFailure.EVENT_GAP
            }
            gapConsumer.stop()

            assertThat(groupOffset(admin, partition)).isEqualTo(1)
            assertProjection(memberId, expectedVersion = 7, expectedStatus = "ELIGIBLE")
            assertThat(dsl.fetchCount(CIRCULATION_CONSUMER_INBOX)).isZero()

            admin.alterConsumerGroupOffsets(
                GROUP_ID,
                mapOf(partition to OffsetAndMetadata(0)),
            ).all().get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)
            awaitCondition { groupOffset(admin, partition) == 0L }

            val recoveryConsumer = consumer(properties)
            recoveryConsumer.start()
            awaitCondition {
                projectionVersion(memberId) == 9L && groupOffset(admin, partition) == 2L
            }
            recoveryConsumer.stop()

            assertProjection(memberId, expectedVersion = 9, expectedStatus = "ELIGIBLE")
            assertThat(dsl.fetchCount(CIRCULATION_CONSUMER_INBOX)).isEqualTo(2)
            assertThat(groupOffset(admin, partition)).isEqualTo(2)
        }
    }

    private fun createAndVerifyTopic(admin: Admin) {
        admin.createTopics(
            listOf(
                NewTopic(TOPIC, 1, 1.toShort()).configs(
                    mapOf(
                        TopicConfig.CLEANUP_POLICY_CONFIG to TopicConfig.CLEANUP_POLICY_DELETE,
                        TopicConfig.RETENTION_MS_CONFIG to RETENTION.toMillis().toString(),
                        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG to "1",
                    ),
                ),
            ),
        ).all().get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)

        val description = admin.describeTopics(listOf(TOPIC))
            .allTopicNames()
            .get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)
            .getValue(TOPIC)
        assertThat(description.partitions()).hasSize(1)
        assertThat(description.partitions().single().replicas()).hasSize(1)

        val resource = ConfigResource(ConfigResource.Type.TOPIC, TOPIC)
        val configuration = admin.describeConfigs(listOf(resource))
            .all()
            .get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)
            .getValue(resource)
        assertThat(configuration.value(TopicConfig.CLEANUP_POLICY_CONFIG))
            .isEqualTo(TopicConfig.CLEANUP_POLICY_DELETE)
        assertThat(configuration.value(TopicConfig.RETENTION_MS_CONFIG))
            .isEqualTo(RETENTION.toMillis().toString())
    }

    private fun bootstrap(memberId: MemberId, snapshotTime: Instant) {
        val item = MembershipEligibilityBootstrapItem(
            memberId = memberId,
            status = MemberEligibilityStatus.ELIGIBLE,
            reasonCode = null,
            sourceVersion = 7,
            sourceOccurredAt = snapshotTime,
            contentSha256 = itemDigest(memberId, snapshotTime),
        )
        val result = bootstrapService.bootstrap(
            MembershipEligibilityBootstrapCommand(
                bootstrapId = UUID.randomUUID(),
                sourceRevision = "a".repeat(64),
                items = listOf(item),
                actorFingerprint = "b".repeat(64),
            ),
        )
        assertThat(result.memberCount).isOne()
        assertProjection(memberId, expectedVersion = 7, expectedStatus = "ELIGIBLE")
    }

    private fun consumer(
        properties: MembershipEligibilityConsumerProperties,
    ): MembershipEligibilityKafkaConsumer {
        val configuration = MembershipEligibilityConsumerConfiguration()
        return MembershipEligibilityKafkaConsumer(
            consumer = configuration.membershipEligibilityKafkaClient(properties),
            decoder = configuration.membershipEligibilityRecordDecoder(properties),
            applyEligibilityEvent = applyEligibilityEvent,
            timeProvider = timeProvider,
            properties = properties,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    private fun seedGroupOffset(
        properties: MembershipEligibilityConsumerProperties,
        partition: TopicPartition,
        offset: Long,
    ) {
        MembershipEligibilityConsumerConfiguration()
            .membershipEligibilityKafkaClient(properties)
            .use { consumer ->
                consumer.assign(listOf(partition))
                consumer.commitSync(
                    mapOf(partition to OffsetAndMetadata(offset)),
                    properties.commitTimeout,
                )
            }
    }

    private fun groupOffset(admin: Admin, partition: TopicPartition): Long? =
        admin.listConsumerGroupOffsets(GROUP_ID)
            .partitionsToOffsetAndMetadata()
            .get(OPERATION_TIMEOUT.seconds, TimeUnit.SECONDS)[partition]
            ?.offset()

    private fun producer(): KafkaProducer<String, ByteArray> = KafkaProducer(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 10_000,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 5_000,
            ProducerConfig.MAX_BLOCK_MS_CONFIG to 5_000,
        ),
    )

    private fun eligibilityRecord(
        memberId: MemberId,
        aggregateVersion: Long,
        status: ContractEligibilityStatus,
        occurredAt: Instant,
        reasonCode: String? = null,
    ): ProducerRecord<String, ByteArray> {
        val eventId = UUID.randomUUID()
        val message = MemberEligibilityChanged.newBuilder()
            .setEventId(eventId.toString())
            .setEventType(MembershipEligibilityEvent.EVENT_TYPE)
            .setEventVersion(MembershipEligibilityEvent.EVENT_VERSION)
            .setMemberId(memberId.value.toString())
            .setAggregateVersion(aggregateVersion)
            .setStatus(status)
            .setOccurredAt(
                Timestamp.newBuilder()
                    .setSeconds(occurredAt.epochSecond)
                    .setNanos(occurredAt.nano)
                    .build(),
            )
            .apply { reasonCode?.let(::setReasonCode) }
            .build()
        return ProducerRecord(TOPIC, 0, memberId.value.toString(), message.toByteArray()).also {
            it.headers()
                .add("content-type", utf8("application/x-protobuf"))
                .add("event-id", utf8(eventId.toString()))
                .add("event-type", utf8(MembershipEligibilityEvent.EVENT_TYPE))
                .add("event-version", utf8(MembershipEligibilityEvent.EVENT_VERSION.toString()))
                .add("schema-subject", utf8(SCHEMA_SUBJECT))
                .add("schema-version", utf8("1"))
        }
    }

    private fun assertProjection(
        memberId: MemberId,
        expectedVersion: Long,
        expectedStatus: String,
    ) {
        val projection = dsl.selectFrom(CIRCULATION_MEMBER_ELIGIBILITY)
            .where(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID.eq(memberId.value))
            .fetchSingle()
        assertThat(projection.sourceVersion).isEqualTo(expectedVersion)
        assertThat(projection.status).isEqualTo(expectedStatus)
        assertThat(projection.reasonCode).isNull()
    }

    private fun projectionVersion(memberId: MemberId): Long? =
        dsl.select(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION)
            .from(CIRCULATION_MEMBER_ELIGIBILITY)
            .where(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID.eq(memberId.value))
            .fetchOne(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION)

    private fun itemDigest(memberId: MemberId, snapshotTime: Instant): String {
        val canonical = buildString {
            append("circulation-membership-eligibility-item-v1")
            field(memberId.value.toString())
            field(MemberEligibilityStatus.ELIGIBLE.name)
            field("<null>")
            field("7")
            field(snapshotTime.toString())
        }
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun StringBuilder.field(value: String) {
        append('\u001f').append(value.length).append(':').append(value)
    }

    private fun org.apache.kafka.clients.admin.Config.value(name: String): String? =
        get(name)?.let(ConfigEntry::value)

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos()
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Condition did not become true before timeout")
            }
            TimeUnit.MILLISECONDS.sleep(25)
        }
    }

    private fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    private fun adminProperties(): Map<String, Any> = mapOf(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG to 5_000,
        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG to 10_000,
    )

    private fun consumerProperties(): MembershipEligibilityConsumerProperties =
        MembershipEligibilityConsumerProperties(
            enabled = true,
            instanceId = "eligibility-broker-replay-integration",
            groupId = GROUP_ID,
            topic = TOPIC,
            schemaSubject = SCHEMA_SUBJECT,
            schemaVersion = 1,
            pollTimeout = Duration.ofMillis(100),
            commitTimeout = Duration.ofSeconds(5),
            retryBackoff = Duration.ofMillis(25),
            startupGracePeriod = Duration.ofSeconds(5),
            maximumPollSilence = Duration.ofSeconds(5),
            maximumPollRecords = 10,
            maximumEventBytes = 65_536,
            kafka = MembershipEligibilityConsumerProperties.KafkaProperties(
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
                requestTimeout = Duration.ofSeconds(5),
                sessionTimeout = Duration.ofSeconds(6),
                heartbeatInterval = Duration.ofSeconds(1),
            ),
        )

    private companion object {
        const val TOPIC = "mundia.membership.events.cutover-replay.v1"
        const val GROUP_ID = "mundia-circulation-membership-cutover-replay-v1"
        const val SCHEMA_SUBJECT = "mundia.membership.v1.MemberEligibilityChanged"
        val RETENTION: Duration = Duration.ofDays(30)
        val OPERATION_TIMEOUT: Duration = Duration.ofSeconds(20)

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("circulation_broker_replay")
            .withUsername("circulation")
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
