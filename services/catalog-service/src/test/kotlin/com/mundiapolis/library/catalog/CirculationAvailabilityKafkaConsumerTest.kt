package com.mundiapolis.library.catalog

import com.google.protobuf.Timestamp
import com.mundiapolis.library.catalog.adapter.`in`.events.CirculationAvailabilityKafkaConsumer
import com.mundiapolis.library.catalog.adapter.`in`.events.CirculationConsumerFailure
import com.mundiapolis.library.catalog.adapter.`in`.events.CirculationEventRecordDecoder
import com.mundiapolis.library.catalog.config.CirculationConsumerProperties
import com.mundiapolis.library.catalog.dto.AvailabilityEventExecution
import com.mundiapolis.library.catalog.dto.ConsumerEventDisposition
import com.mundiapolis.library.catalog.dto.CirculationEventGapException
import com.mundiapolis.library.catalog.service.CirculationAvailabilityEventHandler
import com.mundiapolis.library.circulation.contract.v1.CirculationEvent
import com.mundiapolis.library.circulation.contract.v1.CopyEvent
import com.mundiapolis.library.circulation.contract.v1.CopyStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CirculationAvailabilityKafkaConsumerTest {
    private val properties = properties()

    @Test
    fun `commits the offset only after the projection transaction succeeds`() {
        val kafka = RecordingMockConsumer()
        val partition = TopicPartition(properties.topic, 0)
        val applied = CountDownLatch(1)
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(copyRecord())
        }
        val handler = CirculationAvailabilityEventHandler {
            applied.countDown()
            AvailabilityEventExecution(ConsumerEventDisposition.APPLIED, replayed = false)
        }
        val consumer = CirculationAvailabilityKafkaConsumer(
            kafka,
            CirculationEventRecordDecoder(properties),
            handler,
            Clock.systemUTC(),
            properties,
            SimpleMeterRegistry(),
        )

        consumer.start()
        assertThat(applied.await(5, TimeUnit.SECONDS)).isTrue()
        awaitCondition { kafka.committed(setOf(partition))[partition]?.offset() == 1L }
        val committedOffset = kafka.committed(setOf(partition))[partition]?.offset()
        consumer.stop()

        assertThat(committedOffset).isEqualTo(1L)
        assertThat(kafka.commitCount).isEqualTo(1)
    }

    @Test
    fun `leaves the offset uncommitted and fails readiness on an event gap`() {
        val kafka = RecordingMockConsumer()
        val partition = TopicPartition(properties.topic, 0)
        val attempted = CountDownLatch(1)
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(copyRecord())
        }
        val consumer = CirculationAvailabilityKafkaConsumer(
            kafka,
            CirculationEventRecordDecoder(properties),
            CirculationAvailabilityEventHandler {
                attempted.countDown()
                throw CirculationEventGapException(expected = 0, actual = 2)
            },
            Clock.systemUTC(),
            properties,
            SimpleMeterRegistry(),
        )

        consumer.start()
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue()
        awaitCondition { !consumer.isRunning }
        val health = consumer.healthSnapshot(Instant.now())
        consumer.stop()

        assertThat(kafka.commitCount).isZero()
        assertThat(health.ready).isFalse()
        assertThat(health.failure).isEqualTo(CirculationConsumerFailure.EVENT_GAP)
    }

    private fun copyRecord(): ConsumerRecord<String, ByteArray> {
        val eventId = UUID.randomUUID()
        val copyId = UUID.randomUUID()
        val envelope = CirculationEvent.newBuilder()
            .setEventId(eventId.toString())
            .setEventType("circulation.copy.registered")
            .setEventVersion(1)
            .setAggregateType("copy")
            .setAggregateId(copyId.toString())
            .setAggregateVersion(0)
            .setOccurredAt(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond))
            .setCopy(
                CopyEvent.newBuilder()
                    .setCopyId(copyId.toString())
                    .setEditionId(UUID.randomUUID().toString())
                    .setBranchId(UUID.randomUUID().toString())
                    .setBarcode("COPY-200")
                    .setStatus(CopyStatus.COPY_STATUS_AVAILABLE)
                    .setCopyVersion(0)
                    .setReason("Initial registration"),
            )
            .build()
        return ConsumerRecord(properties.topic, 0, 0, copyId.toString(), envelope.toByteArray())
            .also { record ->
                fun header(name: String, value: String) {
                    record.headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
                }
                header("content-type", "application/x-protobuf")
                header("event-id", eventId.toString())
                header("event-type", envelope.eventType)
                header("event-version", "1")
                header("schema-subject", properties.schemaSubject)
                header("schema-version", "1")
            }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("Condition did not become true")
            TimeUnit.MILLISECONDS.sleep(10)
        }
    }

    private fun properties() = CirculationConsumerProperties(
        enabled = true,
        instanceId = "catalog-consumer-test",
        groupId = "catalog-consumer-test",
        topic = "mundia.circulation.events.v1",
        schemaSubject = "mundia.circulation.v1.CirculationEvent",
        schemaVersion = 1,
        pollTimeout = Duration.ofMillis(100),
        commitTimeout = Duration.ofSeconds(1),
        retryBackoff = Duration.ofMillis(10),
        startupGracePeriod = Duration.ofSeconds(1),
        maximumPollSilence = Duration.ofSeconds(1),
        maximumPollRecords = 10,
        maximumEventBytes = 4_096,
        kafka = CirculationConsumerProperties.KafkaProperties(
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

    private class RecordingMockConsumer : MockConsumer<String, ByteArray>("earliest") {
        private val commits = AtomicInteger()

        val commitCount: Int
            get() = commits.get()

        override fun commitSync(
            offsets: Map<TopicPartition, OffsetAndMetadata>,
            timeout: Duration,
        ) {
            commits.incrementAndGet()
            super.commitSync(offsets, timeout)
        }
    }
}
