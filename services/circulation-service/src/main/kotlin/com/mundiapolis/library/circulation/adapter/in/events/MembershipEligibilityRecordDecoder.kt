package com.mundiapolis.library.circulation.adapter.`in`.events

import com.google.protobuf.InvalidProtocolBufferException
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityEvent
import com.mundiapolis.library.circulation.config.MembershipEligibilityConsumerProperties
import com.mundiapolis.library.circulation.domain.model.EligibilityReasonCode
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityChanged
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityStatus as ContractStatus
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Headers
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID

class MembershipEligibilityRecordDecoder(
    private val properties: MembershipEligibilityConsumerProperties,
) {
    fun decode(record: ConsumerRecord<String, ByteArray>): MembershipEligibilityEvent {
        contract(record.topic() == properties.topic, "Unexpected membership event topic")
        val payload = record.value()
            ?: throw MembershipEventContractException("Membership event payload is absent")
        contract(
            payload.size in 1..properties.maximumEventBytes,
            "Membership event payload size is invalid",
        )
        contract(
            record.headers().requiredUtf8("content-type") == PROTOBUF_CONTENT_TYPE,
            "Membership event content type is invalid",
        )
        contract(
            record.headers().requiredUtf8("event-type") == MembershipEligibilityEvent.EVENT_TYPE,
            "Membership event type header is invalid",
        )
        contract(
            record.headers().requiredUtf8("event-version") ==
                MembershipEligibilityEvent.EVENT_VERSION.toString(),
            "Membership event version header is invalid",
        )
        contract(
            record.headers().requiredUtf8("schema-subject") == properties.schemaSubject,
            "Membership event schema subject is invalid",
        )
        contract(
            record.headers().requiredUtf8("schema-version") == properties.schemaVersion.toString(),
            "Membership event schema version is invalid",
        )

        val message = try {
            MemberEligibilityChanged.parseFrom(payload)
        } catch (_: InvalidProtocolBufferException) {
            throw MembershipEventContractException("Membership event protobuf is invalid")
        }
        val eventId = canonicalUuid(message.eventId, "event_id")
        val memberId = canonicalUuid(message.memberId, "member_id")
        contract(
            record.headers().requiredUtf8("event-id") == eventId.toString(),
            "Membership event identifier header does not match its payload",
        )
        contract(
            record.key() == memberId.toString(),
            "Membership event key does not match its member identifier",
        )
        contract(
            message.eventType == MembershipEligibilityEvent.EVENT_TYPE,
            "Membership event type is invalid",
        )
        contract(
            message.eventVersion == MembershipEligibilityEvent.EVENT_VERSION,
            "Membership event version is invalid",
        )
        contract(message.hasOccurredAt(), "Membership event occurrence time is absent")

        val occurredAt = try {
            val timestamp = message.occurredAt
            contract(
                timestamp.nanos in 0..999_999_999,
                "Membership event occurrence time is invalid",
            )
            Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
        } catch (_: DateTimeException) {
            throw MembershipEventContractException("Membership event occurrence time is invalid")
        } catch (_: ArithmeticException) {
            throw MembershipEventContractException("Membership event occurrence time is invalid")
        }
        val status = when (message.status) {
            ContractStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE ->
                MemberEligibilityStatus.ELIGIBLE
            ContractStatus.MEMBER_ELIGIBILITY_STATUS_INELIGIBLE ->
                MemberEligibilityStatus.INELIGIBLE
            ContractStatus.MEMBER_ELIGIBILITY_STATUS_SUSPENDED ->
                MemberEligibilityStatus.SUSPENDED
            ContractStatus.MEMBER_ELIGIBILITY_STATUS_UNSPECIFIED,
            ContractStatus.UNRECOGNIZED,
            -> throw MembershipEventContractException("Membership eligibility status is invalid")
        }
        val reasonCode = try {
            if (message.hasReasonCode()) {
                EligibilityReasonCode.parse(message.reasonCode)
            } else {
                null
            }
        } catch (_: IllegalArgumentException) {
            throw MembershipEventContractException("Membership eligibility reason is invalid")
        }

        return try {
            MembershipEligibilityEvent(
                eventId = eventId,
                eventType = message.eventType,
                eventVersion = message.eventVersion,
                memberId = MemberId(memberId),
                aggregateVersion = message.aggregateVersion,
                status = status,
                reasonCode = reasonCode,
                occurredAt = occurredAt,
            )
        } catch (_: IllegalArgumentException) {
            throw MembershipEventContractException("Membership eligibility event is inconsistent")
        }
    }

    private fun Headers.requiredUtf8(name: String): String {
        val matching = headers(name).toList()
        contract(matching.size == 1, "Membership event header $name must occur exactly once")
        val bytes = matching.single().value()
        contract(bytes.size in 1..MAX_HEADER_BYTES, "Membership event header $name is invalid")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw MembershipEventContractException("Membership event header $name is invalid")
        }
    }

    private fun canonicalUuid(raw: String, field: String): UUID {
        val value = try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw MembershipEventContractException("Membership event $field is invalid")
        }
        contract(value.toString() == raw, "Membership event $field is not canonical")
        return value
    }

    private fun contract(condition: Boolean, message: String) {
        if (!condition) throw MembershipEventContractException(message)
    }

    private companion object {
        const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"
        const val MAX_HEADER_BYTES = 512
    }
}

class MembershipEventContractException(message: String) : RuntimeException(message)
