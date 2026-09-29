package com.mundiapolis.library.notification

import com.google.protobuf.Timestamp
import com.mundiapolis.library.notification.adapter.`in`.events.NotificationIntentContractException
import com.mundiapolis.library.notification.adapter.`in`.events.NotificationIntentRecordDecoder
import com.mundiapolis.library.notification.config.NotificationIntentConsumerProperties
import com.mundiapolis.library.notification.contract.v1.DeliveryChannel
import com.mundiapolis.library.notification.contract.v1.NotificationCategory
import com.mundiapolis.library.notification.contract.v1.NotificationIntent
import com.mundiapolis.library.notification.dto.NotificationChannel
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

class NotificationIntentRecordDecoderTest {
    private val properties = properties()
    private val decoder = NotificationIntentRecordDecoder(properties)

    @Test
    fun `decodes a bounded canonical protobuf intent`() {
        val eventId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val decoded = decoder.decode(record(intent(eventId, memberId)))

        assertThat(decoded.eventId).isEqualTo(eventId)
        assertThat(decoded.memberId).isEqualTo(memberId)
        assertThat(decoded.channels).containsExactlyInAnyOrder(NotificationChannel.IN_APP, NotificationChannel.EMAIL)
        assertThat(decoded.payloadSha256).matches("[0-9a-f]{64}")
        assertThat(decoded.partition).isZero()
        assertThat(decoded.offset).isEqualTo(10)
    }

    @Test
    fun `rejects duplicate security headers and member key substitution`() {
        val eventId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val duplicate = record(intent(eventId, memberId)).also {
            it.headers().add("event-id", eventId.toString().toByteArray(StandardCharsets.UTF_8))
        }
        assertThatThrownBy { decoder.decode(duplicate) }
            .isInstanceOf(NotificationIntentContractException::class.java)

        val substituted = record(intent(eventId, memberId), key = UUID.randomUUID().toString())
        assertThatThrownBy { decoder.decode(substituted) }
            .isInstanceOf(NotificationIntentContractException::class.java)
    }

    @Test
    fun `rejects duplicate or unspecified channels`() {
        val eventId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val duplicate = intent(eventId, memberId).toBuilder()
            .addChannels(DeliveryChannel.DELIVERY_CHANNEL_IN_APP)
            .build()
        assertThatThrownBy { decoder.decode(record(duplicate)) }
            .isInstanceOf(NotificationIntentContractException::class.java)

        val unspecified = intent(eventId, memberId).toBuilder()
            .clearChannels()
            .addChannels(DeliveryChannel.DELIVERY_CHANNEL_UNSPECIFIED)
            .build()
        assertThatThrownBy { decoder.decode(record(unspecified)) }
            .isInstanceOf(NotificationIntentContractException::class.java)
    }

    private fun intent(eventId: UUID, memberId: UUID): NotificationIntent = NotificationIntent.newBuilder()
        .setEventId(eventId.toString())
        .setEventVersion(1)
        .setSourceType("circulation.loan.due-soon.v1")
        .setMemberId(memberId.toString())
        .setCategory(NotificationCategory.NOTIFICATION_CATEGORY_DUE_SOON)
        .setSubject("Loan due soon")
        .setBody("A borrowed title is due soon.")
        .setOccurredAt(Timestamp.newBuilder().setSeconds(Instant.now().minusSeconds(1).epochSecond))
        .addChannels(DeliveryChannel.DELIVERY_CHANNEL_IN_APP)
        .addChannels(DeliveryChannel.DELIVERY_CHANNEL_EMAIL)
        .build()

    private fun record(
        intent: NotificationIntent,
        key: String = intent.memberId,
    ): ConsumerRecord<String, ByteArray> {
        val record = ConsumerRecord(properties.topic, 0, 10, key, intent.toByteArray())
        fun header(name: String, value: String) {
            record.headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
        }
        header("content-type", "application/x-protobuf")
        header("event-id", intent.eventId)
        header("event-type", "notification.intent.requested")
        header("event-version", intent.eventVersion.toString())
        header("schema-subject", properties.schemaSubject)
        header("schema-version", properties.schemaVersion.toString())
        return record
    }

    private fun properties() = NotificationIntentConsumerProperties(
        enabled = true,
        instanceId = "notification-test",
        groupId = "notification-test",
        topic = "mundia.notification.intents.v1",
        schemaSubject = "mundia.notification.v1.NotificationIntent",
        schemaVersion = 1,
        pollTimeout = Duration.ofMillis(500),
        commitTimeout = Duration.ofSeconds(5),
        retryBackoff = Duration.ofMillis(100),
        startupGracePeriod = Duration.ofSeconds(30),
        maximumPollSilence = Duration.ofSeconds(30),
        maximumPollRecords = 10,
        maximumEventBytes = 262_144,
        kafka = NotificationIntentConsumerProperties.KafkaProperties(
            bootstrapServers = listOf("localhost:9092"),
            securityProtocol = "PLAINTEXT",
            allowInsecureTransport = true,
            saslMechanism = null,
            saslJaasConfig = null,
            truststoreLocation = null,
            truststorePassword = null,
            keystoreLocation = null,
            keystorePassword = null,
            keyPassword = null,
            requestTimeout = Duration.ofSeconds(10),
            sessionTimeout = Duration.ofSeconds(30),
            heartbeatInterval = Duration.ofSeconds(3),
        ),
    )
}
