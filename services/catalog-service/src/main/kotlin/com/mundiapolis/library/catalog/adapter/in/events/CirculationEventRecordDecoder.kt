package com.mundiapolis.library.catalog.adapter.`in`.events

import com.google.protobuf.InvalidProtocolBufferException
import com.mundiapolis.library.catalog.config.CirculationConsumerProperties
import com.mundiapolis.library.catalog.dto.CirculationCopyEvent
import com.mundiapolis.library.catalog.dto.DecodedCirculationRecord
import com.mundiapolis.library.catalog.dto.ProjectedCopyStatus
import com.mundiapolis.library.circulation.contract.v1.CirculationEvent
import com.mundiapolis.library.circulation.contract.v1.CopyStatus
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

class CirculationEventRecordDecoder(
    private val properties: CirculationConsumerProperties,
) {
    fun decode(record: ConsumerRecord<String, ByteArray>): DecodedCirculationRecord {
        contract(record.topic() == properties.topic, "Unexpected circulation event topic")
        val payload = record.value()
            ?: throw CirculationEventContractException("Circulation event payload is absent")
        contract(payload.size in 1..properties.maximumEventBytes, "Circulation event payload size is invalid")
        contract(
            record.headers().requiredUtf8("content-type") == PROTOBUF_CONTENT_TYPE,
            "Circulation event content type is invalid",
        )
        contract(
            record.headers().requiredUtf8("schema-subject") == properties.schemaSubject,
            "Circulation event schema subject is invalid",
        )
        contract(
            record.headers().requiredUtf8("schema-version") == properties.schemaVersion.toString(),
            "Circulation event schema version is invalid",
        )

        val message = try {
            CirculationEvent.parseFrom(payload)
        } catch (_: InvalidProtocolBufferException) {
            throw CirculationEventContractException("Circulation event protobuf is invalid")
        }
        val eventId = canonicalUuid(message.eventId, "event_id")
        val aggregateId = canonicalUuid(message.aggregateId, "aggregate_id")
        contract(message.eventVersion == SUPPORTED_EVENT_VERSION, "Circulation event version is invalid")
        contract(message.eventType.isNotBlank(), "Circulation event type is invalid")
        contract(message.hasOccurredAt(), "Circulation event occurrence time is absent")
        contract(record.key() == aggregateId.toString(), "Circulation event key does not match aggregate")
        contract(
            record.headers().requiredUtf8("event-id") == eventId.toString(),
            "Circulation event identifier header does not match payload",
        )
        contract(
            record.headers().requiredUtf8("event-type") == message.eventType,
            "Circulation event type header does not match payload",
        )
        contract(
            record.headers().requiredUtf8("event-version") == message.eventVersion.toString(),
            "Circulation event version header does not match payload",
        )

        val signalsCopyEvent = message.aggregateType == COPY_AGGREGATE ||
            message.eventType in COPY_EVENT_TYPES || message.hasCopy()
        if (!signalsCopyEvent) return DecodedCirculationRecord.Ignored
        contract(message.aggregateType == COPY_AGGREGATE, "Copy aggregate type is invalid")
        contract(message.eventType in COPY_EVENT_TYPES, "Unsupported copy event type")
        contract(message.hasCopy(), "Copy event payload is absent")
        val copy = message.copy
        val copyId = canonicalUuid(copy.copyId, "copy.copy_id")
        contract(copyId == aggregateId, "Copy identifier does not match aggregate")
        contract(copy.copyVersion == message.aggregateVersion, "Copy version does not match aggregate")
        val editionId = canonicalUuid(copy.editionId, "copy.edition_id")
        val status = copy.status.toProjectedStatus()
        contract(status in COPY_EVENT_STATUSES.getValue(message.eventType), "Copy event status is invalid")
        val occurredAt = message.occurredAt.toInstant()

        return DecodedCirculationRecord.Copy(
            CirculationCopyEvent(
                eventId = eventId,
                eventType = message.eventType,
                eventVersion = message.eventVersion,
                copyId = copyId,
                editionId = editionId,
                aggregateVersion = message.aggregateVersion,
                status = status,
                occurredAt = occurredAt,
                payloadSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
            ),
        )
    }

    private fun CopyStatus.toProjectedStatus(): ProjectedCopyStatus = when (this) {
        CopyStatus.COPY_STATUS_AVAILABLE -> ProjectedCopyStatus.AVAILABLE
        CopyStatus.COPY_STATUS_ON_LOAN -> ProjectedCopyStatus.ON_LOAN
        CopyStatus.COPY_STATUS_RESERVED -> ProjectedCopyStatus.RESERVED
        CopyStatus.COPY_STATUS_LOST -> ProjectedCopyStatus.LOST
        CopyStatus.COPY_STATUS_DAMAGED -> ProjectedCopyStatus.DAMAGED
        CopyStatus.COPY_STATUS_WITHDRAWN -> ProjectedCopyStatus.WITHDRAWN
        CopyStatus.COPY_STATUS_UNSPECIFIED,
        CopyStatus.UNRECOGNIZED,
        -> throw CirculationEventContractException("Copy status is invalid")
    }

    private fun com.google.protobuf.Timestamp.toInstant(): Instant = try {
        contract(nanos in 0..999_999_999, "Circulation event occurrence time is invalid")
        Instant.ofEpochSecond(seconds, nanos.toLong())
    } catch (_: DateTimeException) {
        throw CirculationEventContractException("Circulation event occurrence time is invalid")
    } catch (_: ArithmeticException) {
        throw CirculationEventContractException("Circulation event occurrence time is invalid")
    }

    private fun Headers.requiredUtf8(name: String): String {
        val matching = headers(name).toList()
        contract(matching.size == 1, "Circulation event header $name must occur exactly once")
        val bytes = matching.single().value()
        contract(bytes.size in 1..MAX_HEADER_BYTES, "Circulation event header $name is invalid")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw CirculationEventContractException("Circulation event header $name is invalid")
        }
    }

    private fun canonicalUuid(raw: String, field: String): UUID {
        val value = try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw CirculationEventContractException("Circulation event $field is invalid")
        }
        contract(value.toString() == raw, "Circulation event $field is not canonical")
        return value
    }

    private fun contract(condition: Boolean, message: String) {
        if (!condition) throw CirculationEventContractException(message)
    }

    private companion object {
        const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"
        const val COPY_AGGREGATE = "copy"
        const val SUPPORTED_EVENT_VERSION = 1
        const val MAX_HEADER_BYTES = 512
        val COPY_EVENT_TYPES = setOf(
            "circulation.copy.registered",
            "circulation.copy.condition-changed",
            "circulation.copy.relocated",
            "circulation.copy.status-changed",
        )
        val COPY_EVENT_STATUSES = mapOf(
            "circulation.copy.registered" to setOf(ProjectedCopyStatus.AVAILABLE),
            "circulation.copy.condition-changed" to setOf(
                ProjectedCopyStatus.AVAILABLE,
                ProjectedCopyStatus.LOST,
                ProjectedCopyStatus.DAMAGED,
                ProjectedCopyStatus.WITHDRAWN,
            ),
            "circulation.copy.relocated" to setOf(ProjectedCopyStatus.AVAILABLE),
            "circulation.copy.status-changed" to setOf(
                ProjectedCopyStatus.AVAILABLE,
                ProjectedCopyStatus.ON_LOAN,
                ProjectedCopyStatus.RESERVED,
            ),
        )
    }
}

class CirculationEventContractException(message: String) : RuntimeException(message)
