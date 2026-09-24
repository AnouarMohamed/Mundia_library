package com.mundiapolis.library.catalog.adapter.outbound.events

import com.mundiapolis.library.catalog.config.CatalogOutboxProperties
import com.mundiapolis.library.catalog.dto.BrokerAcknowledgement
import com.mundiapolis.library.catalog.dto.CatalogBrokerPublishException
import com.mundiapolis.library.catalog.dto.CatalogOutboxFailureCode
import com.mundiapolis.library.catalog.dto.EncodedCatalogEvent
import com.mundiapolis.library.catalog.service.CatalogBrokerPublisher
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.TimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class KafkaCatalogEventPublisher(
    private val producer: Producer<String, ByteArray>,
    private val properties: CatalogOutboxProperties,
) : CatalogBrokerPublisher {
    override fun publish(event: EncodedCatalogEvent): BrokerAcknowledgement {
        val record = ProducerRecord(properties.topic, event.key, event.payload)
        record.header("content-type", "application/x-protobuf")
        record.header("event-id", event.eventId.toString())
        record.header("event-type", event.eventType)
        record.header("event-version", event.eventVersion.toString())
        record.header("schema-subject", event.schemaSubject)
        record.header("schema-version", event.schemaVersion.toString())
        return try {
            val metadata = producer.send(record).get(
                properties.kafka.deliveryTimeout.toMillis(),
                TimeUnit.MILLISECONDS,
            )
            BrokerAcknowledgement(metadata.topic(), metadata.partition(), metadata.offset())
        } catch (exception: Exception) {
            throw CatalogBrokerPublishException(classify(exception))
        }
    }

    private fun ProducerRecord<String, ByteArray>.header(name: String, value: String) {
        headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun classify(exception: Exception): CatalogOutboxFailureCode {
        var cause: Throwable? = exception
        repeat(MAX_CAUSE_DEPTH) {
            when (cause) {
                is AuthenticationException -> return CatalogOutboxFailureCode.BROKER_AUTHENTICATION
                is AuthorizationException -> return CatalogOutboxFailureCode.BROKER_AUTHORIZATION
                is TimeoutException,
                is java.util.concurrent.TimeoutException,
                -> return CatalogOutboxFailureCode.BROKER_TIMEOUT
                is RetriableException -> return CatalogOutboxFailureCode.BROKER_UNAVAILABLE
            }
            val next = cause?.cause
            cause = next?.takeUnless { it === cause }
            if (cause == null) return CatalogOutboxFailureCode.BROKER_REJECTED
        }
        return CatalogOutboxFailureCode.BROKER_REJECTED
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
