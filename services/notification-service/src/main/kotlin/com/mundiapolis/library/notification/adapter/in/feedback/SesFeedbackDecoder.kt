package com.mundiapolis.library.notification.adapter.`in`.feedback

import com.mundiapolis.library.notification.config.SesFeedbackProperties
import com.mundiapolis.library.notification.dto.EmailSuppressionReason
import com.mundiapolis.library.notification.dto.SesFeedbackContractException
import com.mundiapolis.library.notification.dto.SesFeedbackEvent
import com.mundiapolis.library.notification.dto.SesFeedbackType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

fun interface SesFeedbackMessageDecoder {
    fun decode(body: String): SesFeedbackEvent
}

class SesFeedbackDecoder(
    private val objectMapper: ObjectMapper,
    private val signatureVerifier: SnsSignatureVerifier,
    private val properties: SesFeedbackProperties,
) : SesFeedbackMessageDecoder {
    override fun decode(body: String): SesFeedbackEvent {
        val bytes = body.toByteArray(Charsets.UTF_8)
        contract(bytes.size in 1..properties.maximumMessageBytes, "SQS message size is invalid")
        val envelope = parse(body, "SNS envelope")
        signatureVerifier.verify(envelope)
        val snsMessageId = canonicalUuid(envelope.requiredText("MessageId", 64), "SNS message identifier")
        val eventBody = envelope.requiredPayload()
        val event = parse(eventBody, "SES event")
        val type = event.requiredText("eventType", 32).toFeedbackType()
        val mail = event.requiredObject("mail")
        val providerReference = mail.requiredText("messageId", 200)
        contract(providerReference.none(Char::isISOControl), "SES message identifier is invalid")
        val tags = mail.requiredObject("tags")
        val deliveryTags = tags.get("delivery_id")
        contract(deliveryTags != null && deliveryTags.isArray && deliveryTags.size() == 1, "SES delivery tag is invalid")
        val deliveryId = canonicalUuid(deliveryTags[0].takeIf { it.isString }?.stringValue(), "SES delivery identifier")
        val eventAt = event.eventTimestamp(type, mail)
        val suppressionReason = event.suppressionReason(type)
        val envelopeAt = envelope.requiredInstant("Timestamp")
        contract(!eventAt.isBefore(envelopeAt.minus(properties.maximumMessageAge)), "SES event timestamp is too old")
        contract(!eventAt.isAfter(envelopeAt.plus(properties.maximumFutureSkew)), "SES event timestamp is in the future")
        return SesFeedbackEvent(
            snsMessageId,
            deliveryId,
            providerReference,
            type,
            eventAt,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            suppressionReason,
        )
    }

    private fun parse(raw: String, label: String): JsonNode = try {
        objectMapper.readTree(raw)?.takeIf { it.isObject }
            ?: throw SesFeedbackContractException("$label is invalid")
    } catch (failure: SesFeedbackContractException) {
        throw failure
    } catch (_: Exception) {
        throw SesFeedbackContractException("$label is invalid")
    }

    private fun JsonNode.eventTimestamp(type: SesFeedbackType, mail: JsonNode): Instant {
        val container = when (type) {
            SesFeedbackType.DELIVERY -> get("delivery")
            SesFeedbackType.BOUNCE -> get("bounce")
            SesFeedbackType.COMPLAINT -> get("complaint")
            SesFeedbackType.DELIVERY_DELAY -> get("deliveryDelay")
            SesFeedbackType.OPEN -> get("open")
            SesFeedbackType.CLICK -> get("click")
            else -> null
        }
        return (container ?: mail).requiredInstant("timestamp")
    }

    private fun JsonNode.suppressionReason(type: SesFeedbackType): EmailSuppressionReason? = when (type) {
        SesFeedbackType.COMPLAINT -> EmailSuppressionReason.COMPLAINT
        SesFeedbackType.BOUNCE -> when (requiredObject("bounce").requiredText("bounceType", 32)) {
            "Permanent" -> EmailSuppressionReason.PERMANENT_BOUNCE
            "Transient", "Undetermined" -> null
            else -> throw SesFeedbackContractException("SES bounce type is unsupported")
        }
        else -> null
    }

    private fun String.toFeedbackType(): SesFeedbackType = when (this) {
        "Send" -> SesFeedbackType.SEND
        "DeliveryDelay" -> SesFeedbackType.DELIVERY_DELAY
        "Delivery" -> SesFeedbackType.DELIVERY
        "Bounce" -> SesFeedbackType.BOUNCE
        "Complaint" -> SesFeedbackType.COMPLAINT
        "Reject" -> SesFeedbackType.REJECT
        "Rendering Failure" -> SesFeedbackType.RENDERING_FAILURE
        "Open" -> SesFeedbackType.OPEN
        "Click" -> SesFeedbackType.CLICK
        else -> throw SesFeedbackContractException("SES event type is unsupported")
    }

    private fun JsonNode.requiredObject(field: String): JsonNode = get(field)
        ?.takeIf { it.isObject }
        ?: throw SesFeedbackContractException("SES $field is invalid")

    private fun JsonNode.requiredText(field: String, maximum: Int): String {
        val node = get(field)
        contract(node != null && node.isString, "$field is invalid")
        return node.stringValue().also { contract(it.length in 1..maximum, "$field is invalid") }
    }

    private fun JsonNode.requiredPayload(): String {
        val node = get("Message")
        contract(node != null && node.isString, "Message is invalid")
        val text = node.stringValue()
        contract(text.isNotEmpty(), "Message is invalid")
        contract(text.toByteArray(Charsets.UTF_8).size <= properties.maximumMessageBytes, "Message is invalid")
        contract(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }, "Message is invalid")
        return text
    }

    private fun JsonNode.requiredInstant(field: String): Instant = try {
        Instant.parse(requiredText(field, 64))
    } catch (_: Exception) {
        throw SesFeedbackContractException("SES timestamp is invalid")
    }

    private fun canonicalUuid(raw: String?, label: String): UUID {
        val value = try {
            UUID.fromString(raw)
        } catch (_: Exception) {
            throw SesFeedbackContractException("$label is invalid")
        }
        contract(value.toString() == raw, "$label is not canonical")
        return value
    }

    private fun contract(condition: Boolean, message: String) {
        if (!condition) throw SesFeedbackContractException(message)
    }
}
