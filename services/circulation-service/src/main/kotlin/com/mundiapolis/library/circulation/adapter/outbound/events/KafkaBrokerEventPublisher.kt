package com.mundiapolis.library.circulation.adapter.outbound.events

import com.mundiapolis.library.circulation.application.model.BrokerPublishAcknowledgement
import com.mundiapolis.library.circulation.application.model.BrokerPublishException
import com.mundiapolis.library.circulation.application.model.EncodedOutboxEvent
import com.mundiapolis.library.circulation.application.model.OutboxFailureCode
import com.mundiapolis.library.circulation.application.port.outbound.BrokerEventPublisher
import com.mundiapolis.library.circulation.config.OutboxDeliveryProperties
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.TimeoutException

class KafkaBrokerEventPublisher(
    private val producer: Producer<String, ByteArray>,
    private val properties: OutboxDeliveryProperties,
) : BrokerEventPublisher {
    override fun publish(event: EncodedOutboxEvent): BrokerPublishAcknowledgement {
        val record = ProducerRecord(properties.topic, event.key, event.payload)
        record.header("content-type", "application/x-protobuf")
        record.header("event-id", event.eventId.toString())
        record.header("event-type", event.eventType)
        record.header("event-version", event.eventVersion.toString())
        record.header("schema-subject", event.schemaSubject)
        record.header("schema-version", event.schemaVersion.toString())

        return try {
            val metadata =
                producer.send(record).get(
                    properties.kafka.deliveryTimeout.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
            BrokerPublishAcknowledgement(
                topic = metadata.topic(),
                partition = metadata.partition(),
                offset = metadata.offset(),
            )
        } catch (exception: Exception) {
            throw BrokerPublishException(classify(exception))
        }
    }

    private fun ProducerRecord<String, ByteArray>.header(name: String, value: String) {
        headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun classify(exception: Exception): OutboxFailureCode {
        var cause: Throwable? = exception
        repeat(MAX_CAUSE_DEPTH) {
            when (cause) {
                is AuthenticationException -> return OutboxFailureCode.BROKER_AUTHENTICATION
                is AuthorizationException -> return OutboxFailureCode.BROKER_AUTHORIZATION
                is TimeoutException,
                is java.util.concurrent.TimeoutException,
                -> return OutboxFailureCode.BROKER_TIMEOUT
                is RetriableException -> return OutboxFailureCode.BROKER_UNAVAILABLE
            }

            val next = cause?.cause
            cause = next?.takeUnless { it === cause }
            if (cause == null) return OutboxFailureCode.BROKER_REJECTED
        }

        return OutboxFailureCode.BROKER_REJECTED
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
