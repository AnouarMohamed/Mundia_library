package com.mundiapolis.library.circulation.adapter.outbound.events

import com.mundiapolis.library.circulation.application.model.ClaimedOutboxEvent
import com.mundiapolis.library.circulation.application.model.OutboxEventStream
import com.mundiapolis.library.circulation.config.OutboxDeliveryConfiguration
import com.mundiapolis.library.circulation.config.OutboxDeliveryProperties
import com.mundiapolis.library.notification.contract.v1.NotificationCategory
import com.mundiapolis.library.notification.contract.v1.NotificationIntent
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Testcontainers
class NotificationIntentKafkaIntegrationTest {
    @Test
    fun `production producer delivers the notification contract through a real broker`() {
        val properties = properties()
        val eventId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val occurredAt = Instant.now().minusSeconds(1)
        val claimed = ClaimedOutboxEvent(
            id = eventId,
            stream = OutboxEventStream.NOTIFICATION,
            aggregateType = "notification-intent",
            aggregateId = eventId,
            aggregateVersion = 0,
            eventType = "notification.intent.requested",
            eventVersion = 1,
            occurredAt = occurredAt,
            traceId = null,
            payloadJson = ObjectMapper().writeValueAsString(
                mapOf(
                    "memberId" to memberId.toString(),
                    "sourceType" to "circulation.reservation.ready.v1",
                    "category" to "HOLD_READY",
                    "subject" to "Reserved title ready",
                    "body" to "Your reserved title is ready for collection.",
                    "channels" to listOf("IN_APP", "EMAIL"),
                ),
            ),
            createdAt = occurredAt,
            deliveryAttempt = 1,
            leaseToken = UUID.randomUUID(),
        )
        val encoded = ProtobufOutboxEventEncoder(ObjectMapper(), properties).encode(claimed)
        val producer = OutboxDeliveryConfiguration().outboxKafkaProducer(properties)

        consumer(properties).use { consumer ->
            consumer.subscribe(listOf(properties.notificationTopic))
            producer.use {
                val acknowledgement = KafkaBrokerEventPublisher(it, properties.kafka.deliveryTimeout)
                    .publish(encoded)
                val delivered = awaitRecord(consumer, eventId)
                val intent = NotificationIntent.parseFrom(delivered.value())

                assertThat(delivered.topic()).isEqualTo(properties.notificationTopic)
                assertThat(delivered.key()).isEqualTo(memberId.toString())
                assertThat(delivered.offset()).isEqualTo(acknowledgement.offset)
                assertThat(delivered.header("schema-subject")).isEqualTo(properties.notificationSchemaSubject)
                assertThat(delivered.header("event-id")).isEqualTo(eventId.toString())
                assertThat(intent.eventId).isEqualTo(eventId.toString())
                assertThat(intent.memberId).isEqualTo(memberId.toString())
                assertThat(intent.category).isEqualTo(NotificationCategory.NOTIFICATION_CATEGORY_HOLD_READY)
                consumer.commitSync()
            }
        }
    }

    private fun awaitRecord(
        consumer: KafkaConsumer<String, ByteArray>,
        eventId: UUID,
    ): ConsumerRecord<String, ByteArray> {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).firstOrNull {
                it.header("event-id") == eventId.toString()
            }?.let { return it }
        }
        throw AssertionError("Notification intent was not delivered through Kafka")
    }

    private fun consumer(properties: OutboxDeliveryProperties): KafkaConsumer<String, ByteArray> =
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "circulation-notification-integration-${UUID.randomUUID()}",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
                ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG to properties.maximumEventBytes + 16 * 1024,
            ),
        )

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String =
        String(requireNotNull(headers().lastHeader(name)).value(), StandardCharsets.UTF_8)

    private fun properties(): OutboxDeliveryProperties = OutboxDeliveryProperties(
        enabled = true,
        instanceId = "circulation-notification-integration",
        topic = "mundia.circulation.events.v1",
        schemaSubject = "mundia.circulation.v1.CirculationEvent",
        notificationTopic = "mundia.notification.intents.v1",
        notificationSchemaSubject = "mundia.notification.v1.NotificationIntent",
        pollInterval = Duration.ofMillis(500),
        leaseDuration = Duration.ofSeconds(30),
        batchSize = 2,
        maximumAttempts = 20,
        retryBaseDelay = Duration.ofSeconds(1),
        retryMaximumDelay = Duration.ofMinutes(5),
        publishedRetention = Duration.ofDays(30),
        cleanupInterval = Duration.ofHours(1),
        cleanupBatchSize = 1_000,
        maximumEventBytes = 262_144,
        maximumPendingAge = Duration.ofMinutes(5),
        kafka = OutboxDeliveryProperties.KafkaProperties(
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
            deliveryTimeout = Duration.ofSeconds(5),
            requestTimeout = Duration.ofSeconds(3),
            maximumBlock = Duration.ofSeconds(5),
        ),
    )

    private companion object {
        @Container @JvmStatic
        val kafka = KafkaContainer("apache/kafka-native:4.2.0")
            .withStartupAttempts(3)
            .withStartupTimeout(Duration.ofMinutes(2))
    }
}
