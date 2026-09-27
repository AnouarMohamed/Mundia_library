package com.mundiapolis.library.membership.adapter.outbound.events

import com.google.protobuf.Timestamp
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityChanged
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityStatus
import com.mundiapolis.library.membership.dto.ClaimedMembershipOutboxEvent
import com.mundiapolis.library.membership.dto.EncodedMembershipEvent
import com.mundiapolis.library.membership.dto.MembershipOutboxContractException
import com.mundiapolis.library.membership.dto.MembershipOutboxPayloadTooLargeException
import com.mundiapolis.library.membership.service.MembershipEventEncoder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class ProtobufMembershipEventEncoder(
    private val objectMapper: ObjectMapper,
    private val schemaSubject: String,
    private val maximumEventBytes: Int,
) : MembershipEventEncoder {
    init {
        require(SCHEMA_SUBJECT.matches(schemaSubject)) { "Membership schema subject is invalid" }
        require(maximumEventBytes in 1_024..1_048_576) {
            "Membership maximum event size is invalid"
        }
    }

    override fun encode(event: ClaimedMembershipOutboxEvent): EncodedMembershipEvent {
        requireContract(event.eventType == EVENT_TYPE)
        requireContract(event.eventVersion == CONTRACT_VERSION)
        requireContract(event.aggregateVersion >= 0)
        val payload = parsePayload(event.payloadJson)
        val fields = payload.propertyNames().toSet()
        requireContract(
            payload.isObject && fields.all(PAYLOAD_FIELDS::contains) &&
                fields.containsAll(REQUIRED_PAYLOAD_FIELDS),
        )
        requireContract(payload.requiredUuid("eventId") == event.eventId)
        requireContract(payload.requiredText("eventType", 120) == event.eventType)
        requireContract(payload.requiredInt("eventVersion") == event.eventVersion)
        requireContract(payload.requiredLong("aggregateVersion") == event.aggregateVersion)
        requireContract(payload.requiredInstant("occurredAt") == event.occurredAt)
        val memberId = payload.requiredUuid("memberId")
        requireContract(memberId == event.aggregateId)
        val status = when (payload.requiredText("status", 20)) {
            "ELIGIBLE" -> MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE
            "INELIGIBLE" -> MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_INELIGIBLE
            "SUSPENDED" -> MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_SUSPENDED
            else -> throw MembershipOutboxContractException("Invalid Membership eligibility status")
        }
        val reasonCode = payload.optionalText("reasonCode", 64)
        requireContract(REASON_CODE.matches(reasonCode ?: "") || reasonCode == null)
        requireContract(
            (status == MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE && reasonCode == null) ||
                (status != MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE && reasonCode != null),
        )
        val message = MemberEligibilityChanged.newBuilder()
            .setEventId(event.eventId.toString())
            .setEventType(event.eventType)
            .setEventVersion(event.eventVersion)
            .setMemberId(memberId.toString())
            .setAggregateVersion(event.aggregateVersion)
            .setStatus(status)
            .setOccurredAt(event.occurredAt.toTimestamp())
            .apply { reasonCode?.let(::setReasonCode) }
            .build()
        val bytes = message.toByteArray()
        if (bytes.size > maximumEventBytes) throw MembershipOutboxPayloadTooLargeException()
        return EncodedMembershipEvent(
            eventId = event.eventId,
            key = memberId.toString(),
            eventType = event.eventType,
            eventVersion = event.eventVersion,
            schemaSubject = schemaSubject,
            schemaVersion = CONTRACT_VERSION,
            payload = bytes,
        )
    }

    private fun parsePayload(raw: String): JsonNode = try {
        objectMapper.readTree(raw)
            ?: throw MembershipOutboxContractException("Missing Membership event payload")
    } catch (exception: MembershipOutboxContractException) {
        throw exception
    } catch (_: Exception) {
        throw MembershipOutboxContractException("Invalid Membership event payload")
    }

    private fun JsonNode.requiredText(field: String, maximum: Int): String {
        val value = get(field)
        requireContract(value != null && value.isString)
        val text = value.stringValue()
        requireContract(
            text.isNotEmpty() && text == text.trim() &&
                text.length <= maximum && text.none(Char::isISOControl),
        )
        return text
    }

    private fun JsonNode.optionalText(field: String, maximum: Int): String? {
        val value = get(field) ?: return null
        if (value.isNull) return null
        return requiredText(field, maximum)
    }

    private fun JsonNode.requiredUuid(field: String): UUID {
        val raw = requiredText(field, 36)
        val value = runCatching { UUID.fromString(raw) }.getOrNull()
        requireContract(value != null && value.toString() == raw)
        return requireNotNull(value)
    }

    private fun JsonNode.requiredInt(field: String): Int {
        val value = get(field)
        requireContract(value != null && value.isIntegralNumber && value.canConvertToInt())
        return value.intValue()
    }

    private fun JsonNode.requiredLong(field: String): Long {
        val value = get(field)
        requireContract(value != null && value.isIntegralNumber && value.canConvertToLong())
        return value.longValue()
    }

    private fun JsonNode.requiredInstant(field: String): Instant {
        val raw = requiredText(field, 40)
        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: throw MembershipOutboxContractException("Invalid Membership event timestamp")
    }

    private fun Instant.toTimestamp(): Timestamp = Timestamp.newBuilder()
        .setSeconds(epochSecond)
        .setNanos(nano)
        .build()

    private fun requireContract(condition: Boolean) {
        if (!condition) throw MembershipOutboxContractException("Invalid Membership event contract")
    }

    private companion object {
        const val EVENT_TYPE = "membership.member.eligibility-changed"
        const val CONTRACT_VERSION = 1
        val SCHEMA_SUBJECT = Regex("[A-Za-z0-9._-]{1,249}")
        val REASON_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
        val PAYLOAD_FIELDS = setOf(
            "eventId",
            "eventType",
            "eventVersion",
            "memberId",
            "aggregateVersion",
            "status",
            "reasonCode",
            "occurredAt",
        )
        val REQUIRED_PAYLOAD_FIELDS = PAYLOAD_FIELDS - "reasonCode"
    }
}
