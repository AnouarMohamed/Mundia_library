package com.mundiapolis.library.notification

import com.mundiapolis.library.notification.adapter.`in`.feedback.SesFeedbackMessageDecoder
import com.mundiapolis.library.notification.adapter.`in`.feedback.SesFeedbackQueue
import com.mundiapolis.library.notification.adapter.`in`.feedback.SesFeedbackQueueMessage
import com.mundiapolis.library.notification.adapter.`in`.feedback.SesFeedbackSqsConsumer
import com.mundiapolis.library.notification.config.SesFeedbackProperties
import com.mundiapolis.library.notification.dto.SesFeedbackContractException
import com.mundiapolis.library.notification.dto.SesFeedbackEvent
import com.mundiapolis.library.notification.dto.SesFeedbackExecution
import com.mundiapolis.library.notification.dto.SesFeedbackType
import com.mundiapolis.library.notification.service.SesFeedbackService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SesFeedbackSqsConsumerTest {
    @Test
    fun `SQS message is deleted only after durable reconciliation`() {
        val queue = FakeQueue()
        val consumer = consumer(queue, SesFeedbackMessageDecoder { EVENT })

        consumer.process(MESSAGE)

        assertThat(queue.deleted).containsExactly("receipt-1")
    }

    @Test
    fun `invalid message remains available for the queue redrive policy`() {
        val queue = FakeQueue()
        val consumer = consumer(queue, SesFeedbackMessageDecoder { throw SesFeedbackContractException("invalid") })

        consumer.process(MESSAGE)

        assertThat(queue.deleted).isEmpty()
    }

    private fun consumer(queue: FakeQueue, decoder: SesFeedbackMessageDecoder): SesFeedbackSqsConsumer {
        val service = SesFeedbackService({ _, _ -> SesFeedbackExecution(replayed = false) }, CLOCK)
        return SesFeedbackSqsConsumer(queue, decoder, service, properties(), CLOCK, SimpleMeterRegistry())
    }

    private fun properties() = SesFeedbackProperties(
        true,
        "eu-west-1",
        "https://sqs.eu-west-1.amazonaws.com/111122223333/mundia-ses-feedback",
        "arn:aws:sns:eu-west-1:111122223333:mundia-ses-feedback",
        Duration.ofSeconds(20),
        Duration.ofMinutes(1),
        262_144,
        Duration.ofDays(14),
        Duration.ofMinutes(2),
        Duration.ofMinutes(1),
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        5,
        Duration.ofSeconds(25),
        Duration.ofSeconds(23),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofHours(1),
        32_768,
    )

    private class FakeQueue : SesFeedbackQueue {
        val deleted = mutableListOf<String>()
        override fun receive(): List<SesFeedbackQueueMessage> = emptyList()
        override fun delete(receiptHandle: String) {
            deleted += receiptHandle
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-03T10:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val MESSAGE = SesFeedbackQueueMessage("sqs-message-1", "receipt-1", "payload")
        val EVENT = SesFeedbackEvent(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            UUID.fromString("20000000-0000-4000-8000-000000000001"),
            "ses-message-123",
            SesFeedbackType.DELIVERY,
            NOW,
            "a".repeat(64),
        )
    }
}
