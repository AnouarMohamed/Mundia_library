package com.mundiapolis.library.notification.adapter.`in`.events

import com.google.protobuf.InvalidProtocolBufferException
import com.mundiapolis.library.notification.config.NotificationIntentConsumerProperties
import com.mundiapolis.library.notification.contract.v1.DeliveryChannel
import com.mundiapolis.library.notification.contract.v1.NotificationIntent
import com.mundiapolis.library.notification.dto.NotificationCategory
import com.mundiapolis.library.notification.dto.NotificationChannel
import com.mundiapolis.library.notification.dto.NotificationIntentCommand
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Headers
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class NotificationIntentRecordDecoder(
    private val properties: NotificationIntentConsumerProperties,
) {
    fun decode(record: ConsumerRecord<String, ByteArray>): NotificationIntentCommand {
        contract(record.topic() == properties.topic, "Unexpected notification intent topic")
        contract(record.partition() >= 0 && record.offset() >= 0, "Notification intent position is invalid")
        val payload = record.value()
            ?: throw NotificationIntentContractException("Notification intent payload is absent")
        contract(payload.size in 1..properties.maximumEventBytes, "Notification intent payload size is invalid")
        contract(record.headers().requiredUtf8("content-type") == PROTOBUF_CONTENT_TYPE, "Content type is invalid")
        contract(record.headers().requiredUtf8("schema-subject") == properties.schemaSubject, "Schema subject is invalid")
        contract(
            record.headers().requiredUtf8("schema-version") == properties.schemaVersion.toString(),
            "Schema version is invalid",
        )
        contract(record.headers().requiredUtf8("event-type") == INTENT_EVENT_TYPE, "Event type is invalid")

        val message = try {
            NotificationIntent.parseFrom(payload)
        } catch (_: InvalidProtocolBufferException) {
            throw NotificationIntentContractException("Notification intent protobuf is invalid")
        }
        val eventId = canonicalUuid(message.eventId, "event_id")
        val memberId = canonicalUuid(message.memberId, "member_id")
        contract(message.eventVersion == SUPPORTED_EVENT_VERSION, "Event version is invalid")
        contract(record.key() == memberId.toString(), "Record key does not match member")
        contract(record.headers().requiredUtf8("event-id") == eventId.toString(), "Event identifier is inconsistent")
        contract(
            record.headers().requiredUtf8("event-version") == message.eventVersion.toString(),
            "Event version header is inconsistent",
        )
        contract(message.sourceType.length in 1..100 && SOURCE_TYPE.matches(message.sourceType), "Source type is invalid")
        contract(message.subject == message.subject.trim() && message.subject.length in 1..160, "Subject is invalid")
        contract(message.body == message.body.trim() && message.body.length in 1..2_000, "Body is invalid")
        contract(message.subject.none(Char::isISOControl), "Subject contains control characters")
        contract(message.body.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }, "Body is invalid")
        contract(message.hasOccurredAt(), "Occurrence time is absent")

        val category = when (message.category) {
            com.mundiapolis.library.notification.contract.v1.NotificationCategory.NOTIFICATION_CATEGORY_DUE_SOON ->
                NotificationCategory.DUE_SOON
            com.mundiapolis.library.notification.contract.v1.NotificationCategory.NOTIFICATION_CATEGORY_OVERDUE ->
                NotificationCategory.OVERDUE
            com.mundiapolis.library.notification.contract.v1.NotificationCategory.NOTIFICATION_CATEGORY_HOLD_READY ->
                NotificationCategory.HOLD_READY
            com.mundiapolis.library.notification.contract.v1.NotificationCategory.NOTIFICATION_CATEGORY_ACCOUNT_STATUS ->
                NotificationCategory.ACCOUNT_STATUS
            com.mundiapolis.library.notification.contract.v1.NotificationCategory.NOTIFICATION_CATEGORY_GENERAL ->
                NotificationCategory.GENERAL
            com.mundiapolis.library.notification.contract.v1.NotificationCategory.NOTIFICATION_CATEGORY_UNSPECIFIED,
            com.mundiapolis.library.notification.contract.v1.NotificationCategory.UNRECOGNIZED,
            -> throw NotificationIntentContractException("Category is invalid")
        }
        contract(message.channelsCount in 1..MAXIMUM_CHANNELS, "Channels are invalid")
        val channels = message.channelsList.map { it.toDomain() }
        contract(channels.size == channels.toSet().size, "Channels must be unique")
        contract(NotificationChannel.IN_APP in channels, "In-app delivery is mandatory")

        return NotificationIntentCommand(
            eventId = eventId,
            memberId = memberId,
            sourceType = message.sourceType,
            category = category,
            subject = message.subject,
            body = message.body,
            occurredAt = message.occurredAt.toInstant(),
            channels = channels.toSet(),
            payloadSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
            topic = record.topic(),
            partition = record.partition(),
            offset = record.offset(),
        )
    }

    private fun DeliveryChannel.toDomain(): NotificationChannel = when (this) {
        DeliveryChannel.DELIVERY_CHANNEL_IN_APP -> NotificationChannel.IN_APP
        DeliveryChannel.DELIVERY_CHANNEL_EMAIL -> NotificationChannel.EMAIL
        DeliveryChannel.DELIVERY_CHANNEL_UNSPECIFIED,
        DeliveryChannel.UNRECOGNIZED,
        -> throw NotificationIntentContractException("Delivery channel is invalid")
    }

    private fun com.google.protobuf.Timestamp.toInstant(): Instant = try {
        contract(nanos in 0..999_999_999, "Occurrence time is invalid")
        Instant.ofEpochSecond(seconds, nanos.toLong())
    } catch (_: DateTimeException) {
        throw NotificationIntentContractException("Occurrence time is invalid")
    } catch (_: ArithmeticException) {
        throw NotificationIntentContractException("Occurrence time is invalid")
    }

    private fun Headers.requiredUtf8(name: String): String {
        val matching = headers(name).toList()
        contract(matching.size == 1, "Header $name must occur exactly once")
        val bytes = matching.single().value()
        contract(bytes.size in 1..MAXIMUM_HEADER_BYTES, "Header $name is invalid")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw NotificationIntentContractException("Header $name is invalid")
        }
    }

    private fun canonicalUuid(raw: String, field: String): UUID {
        val value = try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw NotificationIntentContractException("Notification intent $field is invalid")
        }
        contract(value.toString() == raw, "Notification intent $field is not canonical")
        return value
    }

    private fun contract(condition: Boolean, message: String) {
        if (!condition) throw NotificationIntentContractException(message)
    }

    private companion object {
        const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"
        const val INTENT_EVENT_TYPE = "notification.intent.requested"
        const val SUPPORTED_EVENT_VERSION = 1
        const val MAXIMUM_CHANNELS = 2
        const val MAXIMUM_HEADER_BYTES = 512
        val SOURCE_TYPE = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+){1,9}")
    }
}

class NotificationIntentContractException(message: String) : RuntimeException(message)
