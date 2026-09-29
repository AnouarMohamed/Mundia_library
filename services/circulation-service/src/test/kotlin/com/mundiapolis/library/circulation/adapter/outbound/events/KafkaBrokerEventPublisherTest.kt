package com.mundiapolis.library.circulation.adapter.outbound.events

import com.mundiapolis.library.circulation.application.model.BrokerPublishException
import com.mundiapolis.library.circulation.application.model.EncodedOutboxEvent
import com.mundiapolis.library.circulation.application.model.OutboxFailureCode
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.SaslAuthenticationException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class KafkaBrokerEventPublisherTest {
    @Test
    fun `publishes the immutable protobuf envelope contract and returns broker coordinates`() {
        val producer =
            MockProducer<String, ByteArray>(
                true,
                null,
                StringSerializer(),
                ByteArraySerializer(),
            )
        val publisher = KafkaBrokerEventPublisher(producer, Duration.ofSeconds(5))
        val event = event()

        val acknowledgement = publisher.publish(event)

        assertThat(acknowledgement.topic).isEqualTo("mundia.circulation.events.v1")
        assertThat(acknowledgement.partition).isZero()
        assertThat(acknowledgement.offset).isZero()
        val record = producer.history().single()
        assertThat(record.key()).isEqualTo(event.key)
        assertThat(record.value()).containsExactly(*event.payload)
        assertThat(record.headers().lastHeader("content-type").text())
            .isEqualTo("application/x-protobuf")
        assertThat(record.headers().lastHeader("event-id").text())
            .isEqualTo(event.eventId.toString())
        assertThat(record.headers().lastHeader("schema-subject").text())
            .isEqualTo(event.schemaSubject)
        assertThat(record.headers().lastHeader("schema-version").text()).isEqualTo("1")
    }

    @Test
    fun `routes each encoded contract to its declared topic`() {
        val producer = MockProducer<String, ByteArray>(true, null, StringSerializer(), ByteArraySerializer())
        val publisher = KafkaBrokerEventPublisher(producer, Duration.ofSeconds(5))

        publisher.publish(event().copy(topic = "mundia.notification.intents.v1"))

        assertThat(producer.history().single().topic()).isEqualTo("mundia.notification.intents.v1")
    }

    @Test
    fun `sanitizes broker authentication failures into a bounded failure code`() {
        @Suppress("UNCHECKED_CAST")
        val producer = mock(Producer::class.java) as Producer<String, ByteArray>
        val failedPublish = CompletableFuture<RecordMetadata>()
        failedPublish.completeExceptionally(
            KafkaException(
                "wrapper must not escape",
                SaslAuthenticationException("credential material must not escape"),
            ),
        )
        `when`(producer.send(any<ProducerRecord<String, ByteArray>>())).thenReturn(failedPublish)
        val publisher = KafkaBrokerEventPublisher(producer, Duration.ofSeconds(5))

        val failure = assertThrows<BrokerPublishException> { publisher.publish(event()) }

        assertThat(failure.failureCode).isEqualTo(OutboxFailureCode.BROKER_AUTHENTICATION)
        assertThat(failure.message).isEqualTo("Broker publish failed")
        assertThat(failure.cause).isNull()
    }

    private fun event(): EncodedOutboxEvent {
        val aggregateId = UUID.randomUUID()
        return EncodedOutboxEvent(
            eventId = UUID.randomUUID(),
            topic = "mundia.circulation.events.v1",
            key = aggregateId.toString(),
            eventType = "LoanRequested",
            eventVersion = 1,
            aggregateType = "LOAN",
            aggregateId = aggregateId,
            aggregateVersion = 0,
            occurredAt = Instant.parse("2026-08-01T00:00:00Z"),
            schemaSubject = "mundia.circulation.v1.CirculationEvent",
            schemaVersion = 1,
            payload = byteArrayOf(1, 2, 3),
        )
    }

    private fun org.apache.kafka.common.header.Header.text(): String =
        String(value(), StandardCharsets.UTF_8)
}
