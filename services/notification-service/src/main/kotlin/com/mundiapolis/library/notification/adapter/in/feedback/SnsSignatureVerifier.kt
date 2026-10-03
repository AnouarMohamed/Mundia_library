package com.mundiapolis.library.notification.adapter.`in`.feedback

import com.mundiapolis.library.notification.config.SesFeedbackProperties
import com.mundiapolis.library.notification.dto.SesFeedbackContractException
import tools.jackson.databind.JsonNode
import java.net.URI
import java.security.PublicKey
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.util.Base64

fun interface SnsSigningKeyProvider {
    fun get(uri: URI): PublicKey
}

class SnsSignatureVerifier(
    private val keyProvider: SnsSigningKeyProvider,
    private val properties: SesFeedbackProperties,
    private val clock: Clock,
) {
    fun verify(envelope: JsonNode) {
        contract(envelope.requiredText("Type", 32) == "Notification", "SNS message type is invalid")
        contract(envelope.requiredText("TopicArn", 512) == properties.topicArn, "SNS topic is invalid")
        contract(envelope.requiredText("SignatureVersion", 8) == "2", "SNS signature version is invalid")
        val timestamp = envelope.requiredInstant("Timestamp")
        val now = clock.instant()
        contract(!timestamp.isBefore(now.minus(properties.maximumMessageAge)), "SNS message is too old")
        contract(!timestamp.isAfter(now.plus(properties.maximumFutureSkew)), "SNS message timestamp is in the future")

        val certificateUri = parseCertificateUri(envelope.requiredText("SigningCertURL", 1_024))
        val signature = try {
            Base64.getDecoder().decode(envelope.requiredText("Signature", 4_096))
        } catch (_: IllegalArgumentException) {
            throw SesFeedbackContractException("SNS signature encoding is invalid")
        }
        contract(signature.size in 128..1_024, "SNS signature size is invalid")
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(keyProvider.get(certificateUri))
        verifier.update(canonicalMessage(envelope).toByteArray(Charsets.UTF_8))
        contract(verifier.verify(signature), "SNS signature is invalid")
    }

    private fun parseCertificateUri(raw: String): URI {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw SesFeedbackContractException("SNS signing certificate URL is invalid")
        }
        contract(uri.scheme == "https", "SNS signing certificate URL is invalid")
        contract(uri.host == "sns.${properties.region}.amazonaws.com", "SNS signing certificate host is invalid")
        contract(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null, "SNS signing certificate URL is invalid")
        contract(CERTIFICATE_PATH.matches(uri.path), "SNS signing certificate path is invalid")
        return uri
    }

    private fun canonicalMessage(envelope: JsonNode): String = buildString {
        appendField("Message", envelope.requiredMessage())
        appendField("MessageId", envelope.requiredText("MessageId", 64))
        envelope.get("Subject")?.let {
            contract(it.isString, "SNS Subject is invalid")
            appendField("Subject", it.stringValue())
        }
        appendField("Timestamp", envelope.requiredText("Timestamp", 64))
        appendField("TopicArn", envelope.requiredText("TopicArn", 512))
        appendField("Type", envelope.requiredText("Type", 32))
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append(name).append('\n').append(value).append('\n')
    }

    private fun JsonNode.requiredText(field: String, maximum: Int): String {
        val value = get(field)
        contract(value != null && value.isString, "SNS $field is invalid")
        val text = value.stringValue()
        contract(text.length in 1..maximum && text.none(Char::isISOControl), "SNS $field is invalid")
        return text
    }

    private fun JsonNode.requiredMessage(): String {
        val value = get("Message")
        contract(value != null && value.isString, "SNS Message is invalid")
        val text = value.stringValue()
        contract(text.length in 1..properties.maximumMessageBytes, "SNS Message is invalid")
        contract(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }, "SNS Message is invalid")
        return text
    }

    private fun JsonNode.requiredInstant(field: String): Instant = try {
        Instant.parse(requiredText(field, 64))
    } catch (_: Exception) {
        throw SesFeedbackContractException("SNS $field is invalid")
    }

    private fun contract(condition: Boolean, message: String) {
        if (!condition) throw SesFeedbackContractException(message)
    }

    private companion object {
        val CERTIFICATE_PATH = Regex("/SimpleNotificationService-[A-Za-z0-9_-]{1,128}\\.pem")
    }
}
