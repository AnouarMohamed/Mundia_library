package com.mundiapolis.library.notification

import com.google.protobuf.Timestamp
import com.mundiapolis.library.notification.adapter.`in`.events.NotificationIntentConsumerFailure
import com.mundiapolis.library.notification.adapter.`in`.events.NotificationIntentKafkaConsumer
import com.mundiapolis.library.notification.adapter.`in`.events.NotificationIntentRecordDecoder
import com.mundiapolis.library.notification.config.NotificationIntentConsumerProperties
import com.mundiapolis.library.notification.contract.v1.DeliveryChannel
import com.mundiapolis.library.notification.contract.v1.NotificationCategory
import com.mundiapolis.library.notification.contract.v1.NotificationIntent
import com.mundiapolis.library.notification.dto.NotificationIntentExecution
import com.mundiapolis.library.notification.dto.NotificationIntentConflictException
import com.mundiapolis.library.notification.service.NotificationIntentHandler
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

class NotificationIntentKafkaConsumerTest {
    private val properties = properties()

    @Test
    fun `commits only after durable intent handling succeeds`() {
        val kafka = RecordingMockConsumer()
        val partition = TopicPartition(properties.topic, 0)
        val handled = CountDownLatch(1)
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(record())
        }
        val consumer = consumer(kafka) {
            handled.countDown()
            NotificationIntentExecution(UUID.randomUUID(), replayed = false)
        }

        consumer.start()
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue()
        awaitCondition { kafka.committed(setOf(partition))[partition]?.offset() == 1L }
        consumer.stop()

        assertThat(kafka.commitCount).isEqualTo(1)
    }

    @Test
    fun `does not commit a conflicting replay and fails readiness`() {
        val kafka = RecordingMockConsumer()
        val partition = TopicPartition(properties.topic, 0)
        val attempted = CountDownLatch(1)
        kafka.schedulePollTask {
            kafka.rebalance(listOf(partition))
            kafka.updateBeginningOffsets(mapOf(partition to 0L))
            kafka.addRecord(record())
        }
        val consumer = consumer(kafka) {
            attempted.countDown()
            throw NotificationIntentConflictException()
        }

        consumer.start()
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue()
        awaitCondition { !consumer.isRunning }
        val health = consumer.healthSnapshot(Instant.now())
        consumer.stop()

        assertThat(kafka.commitCount).isZero()
        assertThat(health.ready).isFalse()
        assertThat(health.failure).isEqualTo(NotificationIntentConsumerFailure.EVENT_CONFLICT)
    }

    private fun consumer(
        kafka: RecordingMockConsumer,
        handler: NotificationIntentHandler,
    ) = NotificationIntentKafkaConsumer(
        kafka,
        NotificationIntentRecordDecoder(properties),
        handler,
        Clock.systemUTC(),
        properties,
        SimpleMeterRegistry(),
    )

    private fun record(): ConsumerRecord<String, ByteArray> {
        val eventId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val intent = NotificationIntent.newBuilder()
            .setEventId(eventId.toString())
            .setEventVersion(1)
            .setSourceType("circulation.loan.due-soon.v1")
            .setMemberId(memberId.toString())
            .setCategory(NotificationCategory.NOTIFICATION_CATEGORY_DUE_SOON)
            .setSubject("Loan due soon")
            .setBody("A borrowed title is due soon.")
            .setOccurredAt(Timestamp.newBuilder().setSeconds(Instant.now().minusSeconds(1).epochSecond))
            .addChannels(DeliveryChannel.DELIVERY_CHANNEL_IN_APP)
            .build()
        return ConsumerRecord(properties.topic, 0, 0, memberId.toString(), intent.toByteArray()).also { record ->
            fun header(name: String, value: String) {
                record.headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
            }
            header("content-type", "application/x-protobuf")
            header("event-id", eventId.toString())
            header("event-type", "notification.intent.requested")
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

    private fun properties() = NotificationIntentConsumerProperties(
        enabled = true,
        instanceId = "notification-consumer-test",
        groupId = "notification-consumer-test",
        topic = "mundia.notification.intents.v1",
        schemaSubject = "mundia.notification.v1.NotificationIntent",
        schemaVersion = 1,
        pollTimeout = Duration.ofMillis(100),
        commitTimeout = Duration.ofSeconds(1),
        retryBackoff = Duration.ofMillis(10),
        startupGracePeriod = Duration.ofSeconds(1),
        maximumPollSilence = Duration.ofSeconds(1),
        maximumPollRecords = 10,
        maximumEventBytes = 4_096,
        kafka = NotificationIntentConsumerProperties.KafkaProperties(
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
        val commitCount: Int get() = commits.get()

        override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>, timeout: Duration) {
            commits.incrementAndGet()
            super.commitSync(offsets, timeout)
        }
    }
}
