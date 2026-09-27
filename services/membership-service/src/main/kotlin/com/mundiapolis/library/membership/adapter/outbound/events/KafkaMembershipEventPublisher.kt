package com.mundiapolis.library.membership.adapter.outbound.events

import com.mundiapolis.library.membership.config.MembershipOutboxProperties
import com.mundiapolis.library.membership.dto.EncodedMembershipEvent
import com.mundiapolis.library.membership.dto.MembershipBrokerAcknowledgement
import com.mundiapolis.library.membership.dto.MembershipBrokerPublishException
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureCode
import com.mundiapolis.library.membership.service.MembershipBrokerPublisher
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.TimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class KafkaMembershipEventPublisher(
    private val producer: Producer<String, ByteArray>,
    private val properties: MembershipOutboxProperties,
) : MembershipBrokerPublisher {
    override fun publish(event: EncodedMembershipEvent): MembershipBrokerAcknowledgement {
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
            MembershipBrokerAcknowledgement(metadata.topic(), metadata.partition(), metadata.offset())
        } catch (exception: Exception) {
            throw MembershipBrokerPublishException(classify(exception))
        }
    }

    private fun ProducerRecord<String, ByteArray>.header(name: String, value: String) {
        headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun classify(exception: Exception): MembershipOutboxFailureCode {
        var cause: Throwable? = exception
        repeat(MAX_CAUSE_DEPTH) {
            when (cause) {
                is AuthenticationException -> return MembershipOutboxFailureCode.BROKER_AUTHENTICATION
                is AuthorizationException -> return MembershipOutboxFailureCode.BROKER_AUTHORIZATION
                is TimeoutException,
                is java.util.concurrent.TimeoutException,
                -> return MembershipOutboxFailureCode.BROKER_TIMEOUT
                is RetriableException -> return MembershipOutboxFailureCode.BROKER_UNAVAILABLE
            }
            val next = cause?.cause
            cause = next?.takeUnless { it === cause }
            if (cause == null) return MembershipOutboxFailureCode.BROKER_REJECTED
        }
        return MembershipOutboxFailureCode.BROKER_REJECTED
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
