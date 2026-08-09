package com.mundiapolis.library.circulation.adapter.`in`.events

import com.google.protobuf.Timestamp
import com.mundiapolis.library.circulation.application.model.EligibilityEventDisposition
import com.mundiapolis.library.circulation.application.model.EligibilityEventExecution
import com.mundiapolis.library.circulation.application.port.inbound.ApplyMembershipEligibilityEventUseCase
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.config.MembershipEligibilityConsumerConfiguration
import com.mundiapolis.library.circulation.config.MembershipEligibilityConsumerProperties
import com.mundiapolis.library.circulation.domain.model.MemberEligibility
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityChanged
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityStatus as ContractStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.RetriableCommitFailedException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MembershipEligibilityKafkaConsumerTest {
    private val properties = properties()
    private val decoder = MembershipEligibilityRecordDecoder(properties)

    @Test
    fun `decoder authenticates transport metadata and maps the minimal protobuf contract`() {
        val eventId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-08-09T20:00:00.123456Z")

        val decoded = decoder.decode(
            record(
                eventId = eventId,
                memberId = memberId,
                occurredAt = occurredAt,
                status = ContractStatus.MEMBER_ELIGIBILITY_STATUS_SUSPENDED,
                reasonCode = "ACCOUNT_SUSPENDED",
            ),
        )

        assertThat(decoded.eventId).isEqualTo(eventId)
        assertThat(decoded.memberId.value).isEqualTo(memberId)
        assertThat(decoded.aggregateVersion).isEqualTo(7)
        assertThat(decoded.status).isEqualTo(MemberEligibilityStatus.SUSPENDED)
        assertThat(decoded.reasonCode?.value).isEqualTo("ACCOUNT_SUSPENDED")
        assertThat(decoded.occurredAt).isEqualTo(occurredAt)
    }

    @Test
    fun `decoder rejects ambiguous headers wrong partition keys and inconsistent state`() {
        val valid = record()
        valid.headers().add("event-id", utf8(UUID.randomUUID().toString()))
        assertThatThrownBy { decoder.decode(valid) }
            .isInstanceOf(MembershipEventContractException::class.java)
            .hasMessageContaining("exactly once")

        val wrongKey = record(keyOverride = UUID.randomUUID().toString())
        assertThatThrownBy { decoder.decode(wrongKey) }
            .isInstanceOf(MembershipEventContractException::class.java)
            .hasMessageContaining("key")

        val inconsistent = record(
            status = ContractStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE,
            reasonCode = "REASON_NOT_ALLOWED",
        )
        assertThatThrownBy { decoder.decode(inconsistent) }
            .isInstanceOf(MembershipEventContractException::class.java)
            .hasMessageContaining("inconsistent")
    }

    @Test
    fun `decoder classifies an overflowing timestamp as a contract failure`() {
        val overflowing = record(
            timestampOverride = Timestamp.newBuilder()
                .setSeconds(Long.MAX_VALUE)
                .setNanos(999_999_999)
                .build(),
        )

        assertThatThrownBy { decoder.decode(overflowing) }
            .isInstanceOf(MembershipEventContractException::class.java)
            .hasMessageContaining("occurrence time")
    }

    @Test
    fun `consumer commits only after the database backed handler succeeds`() {
        val kafka = RecordingMockConsumer()
        val partition = TopicPartition(properties.topic, 0)
        val applied = CountDownLatch(1)
        val useCase = ApplyMembershipEligibilityEventUseCase { event ->
            applied.countDown()
            EligibilityEventExecution(
                disposition = EligibilityEventDisposition.APPLIED,
                replayed = false,
                eligibility = MemberEligibility(
                    memberId = event.memberId,
                    status = event.status,
                    reasonCode = event.reasonCode,
                    sourceVersion = event.aggregateVersion,
                    sourceOccurredAt = event.occurredAt,
                ),
            )
        }
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(record(offset = 0))
        }
        val consumer = consumer(kafka, useCase)

        consumer.start()
        assertThat(applied.await(5, TimeUnit.SECONDS)).isTrue()
        awaitCondition { kafka.committed(setOf(partition))[partition]?.offset() == 1L }
        assertThat(kafka.committed(setOf(partition))[partition]?.offset()).isEqualTo(1L)
        consumer.stop()
        assertThat(kafka.commitCount).isEqualTo(1)
    }

    @Test
    fun `consumer recovers from a transient poll failure`() {
        val kafka = RecordingMockConsumer()
        val partition = TopicPartition(properties.topic, 0)
        val applied = CountDownLatch(1)
        kafka.schedulePollTask { throw TimeoutException("broker temporarily unavailable") }
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(record(offset = 0))
        }
        val consumer = consumer(kafka) {
            applied.countDown()
            execution(it)
        }

        consumer.start()
        assertThat(applied.await(5, TimeUnit.SECONDS)).isTrue()
        awaitCondition { kafka.committed(setOf(partition))[partition]?.offset() == 1L }
        consumer.stop()

        assertThat(consumer.healthSnapshot(Instant.now()).failure).isNull()
        assertThat(kafka.commitCount).isEqualTo(1)
    }

    @Test
    fun `consumer retries a transient commit failure without advancing the batch`() {
        val kafka = RecordingMockConsumer(failFirstCommit = true)
        val partition = TopicPartition(properties.topic, 0)
        val applied = AtomicInteger()
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(record(offset = 0))
        }
        val consumer = consumer(kafka) {
            applied.incrementAndGet()
            execution(it)
        }

        consumer.start()
        awaitCondition { kafka.committed(setOf(partition))[partition]?.offset() == 1L }
        consumer.stop()

        assertThat(applied.get()).isEqualTo(1)
        assertThat(kafka.commitCount).isEqualTo(2)
        assertThat(consumer.healthSnapshot(Instant.now()).failure).isNull()
    }

    @Test
    fun `consumer startup keeps readiness out of service until partitions are assigned`() {
        val kafka = RecordingMockConsumer()
        val consumer = consumer(kafka) { execution(it) }
        consumer.start()
        awaitCondition { consumer.isRunning }
        val beanFactory = StaticListableBeanFactory(mapOf("consumer" to consumer))
        val health = requireNotNull(
            MembershipEligibilityConsumerConfiguration()
                .membershipEligibilityConsumerHealthIndicator(
                    consumer = beanFactory.getBeanProvider(MembershipEligibilityKafkaConsumer::class.java),
                    properties = properties,
                    timeProvider = TimeProvider(Instant::now),
                )
                .health(),
        )
        consumer.stop()

        assertThat(health.status).isEqualTo(Status.OUT_OF_SERVICE)
    }

    @Test
    fun `poison event blocks progress and reports a readiness failure without committing`() {
        val kafka = RecordingMockConsumer()
        val partition = TopicPartition(properties.topic, 0)
        val poison = record()
        poison.headers().add("event-id", utf8(UUID.randomUUID().toString()))
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(poison)
        }
        val consumer = consumer(kafka) {
            throw AssertionError("A malformed event must not reach the application handler")
        }

        consumer.start()
        awaitCondition {
            consumer.healthSnapshot(Instant.now()).failure == MembershipConsumerFailure.CONTRACT
        }
        assertThat(consumer.healthSnapshot(Instant.now()).ready).isFalse()
        consumer.stop()
        assertThat(kafka.commitCount).isZero()
    }

    private fun consumer(
        kafka: MockConsumer<String, ByteArray>,
        useCase: ApplyMembershipEligibilityEventUseCase,
    ): MembershipEligibilityKafkaConsumer = MembershipEligibilityKafkaConsumer(
        consumer = kafka,
        decoder = decoder,
        applyEligibilityEvent = useCase,
        timeProvider = TimeProvider(Instant::now),
        properties = properties,
        meterRegistry = SimpleMeterRegistry(),
    )

    private fun record(
        eventId: UUID = UUID.randomUUID(),
        memberId: UUID = UUID.randomUUID(),
        occurredAt: Instant = Instant.parse("2026-08-09T20:00:00Z"),
        status: ContractStatus = ContractStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE,
        reasonCode: String? = null,
        keyOverride: String? = null,
        timestampOverride: Timestamp? = null,
        offset: Long = 0,
    ): ConsumerRecord<String, ByteArray> {
        val message = MemberEligibilityChanged.newBuilder()
            .setEventId(eventId.toString())
            .setEventType("membership.member.eligibility-changed")
            .setEventVersion(1)
            .setMemberId(memberId.toString())
            .setAggregateVersion(7)
            .setStatus(status)
            .setOccurredAt(
                timestampOverride ?: Timestamp.newBuilder()
                    .setSeconds(occurredAt.epochSecond)
                    .setNanos(occurredAt.nano)
                    .build(),
            )
            .apply { reasonCode?.let(::setReasonCode) }
            .build()
        return ConsumerRecord(
            properties.topic,
            0,
            offset,
            keyOverride ?: memberId.toString(),
            message.toByteArray(),
        ).also { record ->
            record.headers()
                .add("content-type", utf8("application/x-protobuf"))
                .add("event-id", utf8(eventId.toString()))
                .add("event-type", utf8("membership.member.eligibility-changed"))
                .add("event-version", utf8("1"))
                .add("schema-subject", utf8(properties.schemaSubject))
                .add("schema-version", utf8(properties.schemaVersion.toString()))
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Condition did not become true before timeout")
            }
            TimeUnit.MILLISECONDS.sleep(10)
        }
    }

    private fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    private fun properties(): MembershipEligibilityConsumerProperties =
        MembershipEligibilityConsumerProperties(
            enabled = true,
            instanceId = "membership-consumer-test",
            groupId = "circulation-membership-test-v1",
            topic = "mundia.membership.events.v1",
            schemaSubject = "mundia.membership.v1.MemberEligibilityChanged",
            schemaVersion = 1,
            pollTimeout = Duration.ofMillis(100),
            commitTimeout = Duration.ofSeconds(1),
            retryBackoff = Duration.ofMillis(10),
            startupGracePeriod = Duration.ofSeconds(1),
            maximumPollSilence = Duration.ofSeconds(1),
            maximumPollRecords = 10,
            maximumEventBytes = 4_096,
            kafka = MembershipEligibilityConsumerProperties.KafkaProperties(
                bootstrapServers = listOf("127.0.0.1:9092"),
                securityProtocol = "PLAINTEXT",
                allowInsecureTransport = true,
                saslMechanism = null,
                saslJaasConfig = null,
                truststoreLocation = null,
                truststorePassword = null,
                keystoreLocation = null,
                keystorePassword = null,
                keyPassword = null,
                requestTimeout = Duration.ofSeconds(1),
                sessionTimeout = Duration.ofSeconds(6),
                heartbeatInterval = Duration.ofSeconds(1),
            ),
        )

    private class RecordingMockConsumer(
        private val failFirstCommit: Boolean = false,
    ) : MockConsumer<String, ByteArray>("earliest") {
        private val commits = AtomicInteger()

        val commitCount: Int
            get() = commits.get()

        override fun commitSync(
            offsets: Map<TopicPartition, OffsetAndMetadata>,
            timeout: Duration,
        ) {
            val attempt = commits.incrementAndGet()
            if (failFirstCommit && attempt == 1) {
                throw RetriableCommitFailedException("broker temporarily unavailable")
            }
            super.commitSync(offsets, timeout)
        }
    }

    private fun execution(
        event: com.mundiapolis.library.circulation.application.model.MembershipEligibilityEvent,
    ): EligibilityEventExecution = EligibilityEventExecution(
        disposition = EligibilityEventDisposition.APPLIED,
        replayed = false,
        eligibility = MemberEligibility(
            memberId = event.memberId,
            status = event.status,
            reasonCode = event.reasonCode,
            sourceVersion = event.aggregateVersion,
            sourceOccurredAt = event.occurredAt,
        ),
    )
}
