package com.mundiapolis.library.notification

import com.mundiapolis.library.notification.adapter.`in`.feedback.SesFeedbackDecoder
import com.mundiapolis.library.notification.adapter.`in`.feedback.SnsSignatureVerifier
import com.mundiapolis.library.notification.config.SesFeedbackProperties
import com.mundiapolis.library.notification.dto.SesFeedbackContractException
import com.mundiapolis.library.notification.dto.SesFeedbackType
import com.mundiapolis.library.notification.dto.EmailSuppressionReason
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class SesFeedbackDecoderTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val properties = properties()
    private val verifier = SnsSignatureVerifier({ keyPair.public }, properties, Clock.fixed(NOW, ZoneOffset.UTC))
    private val decoder = SesFeedbackDecoder(mapper, verifier, properties)

    @Test
    fun `valid SHA-256 signed SNS envelope decodes the SES correlation fields`() {
        val decoded = decoder.decode(envelope(event("Delivery", "delivery", NOW.minusSeconds(2).toString())))

        assertThat(decoded.snsMessageId).isEqualTo(SNS_MESSAGE_ID)
        assertThat(decoded.deliveryId).isEqualTo(DELIVERY_ID)
        assertThat(decoded.providerMessageReference).isEqualTo("ses-message-123")
        assertThat(decoded.type).isEqualTo(SesFeedbackType.DELIVERY)
        assertThat(decoded.eventAt).isEqualTo(NOW.minusSeconds(2))
        assertThat(decoded.payloadSha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun `tampered SES payload is rejected before reconciliation`() {
        val signed = envelope(event("Delivery", "delivery", NOW.minusSeconds(2).toString()))
        val tampered = signed.replace("ses-message-123", "ses-message-999")

        assertThatThrownBy { decoder.decode(tampered) }
            .isInstanceOf(SesFeedbackContractException::class.java)
            .hasMessage("SNS signature is invalid")
    }

    @Test
    fun `only permanent bounces and complaints request recipient suppression`() {
        val permanent = decoder.decode(
            envelope(event("Bounce", "bounce", NOW.minusSeconds(2).toString(), "Permanent")),
        )
        val transient = decoder.decode(
            envelope(event("Bounce", "bounce", NOW.minusSeconds(2).toString(), "Transient"), ANOTHER_MESSAGE_ID),
        )
        val complaint = decoder.decode(
            envelope(event("Complaint", "complaint", NOW.minusSeconds(2).toString()), THIRD_MESSAGE_ID),
        )

        assertThat(permanent.suppressionReason).isEqualTo(EmailSuppressionReason.PERMANENT_BOUNCE)
        assertThat(transient.suppressionReason).isNull()
        assertThat(complaint.suppressionReason).isEqualTo(EmailSuppressionReason.COMPLAINT)
    }

    @Test
    fun `certificate URL cannot escape the regional AWS SNS host`() {
        var keyRequested = false
        val guardedVerifier = SnsSignatureVerifier(
            { keyRequested = true; keyPair.public },
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
        val guardedDecoder = SesFeedbackDecoder(mapper, guardedVerifier, properties)

        assertThatThrownBy {
            guardedDecoder.decode(
                envelope(
                    event("Bounce", "bounce", NOW.toString()),
                    certificateUrl = "https://attacker.example/cert.pem",
                ),
            )
        }.isInstanceOf(SesFeedbackContractException::class.java)
        assertThat(keyRequested).isFalse()
    }

    @Test
    fun `unsafe feedback configuration fails closed`() {
        assertThat(properties(enabled = false).isSafeConfiguration).isTrue()
        assertThat(properties(queueUrl = "https://attacker.example/queue").isSafeConfiguration).isFalse()
        assertThat(properties(topicArn = "arn:aws:sns:us-east-1:111122223333:ses-feedback").isSafeConfiguration).isFalse()
        assertThat(properties(maximumMessageBytes = 1_000_000).isSafeConfiguration).isFalse()
        assertThat(properties().isSafeConfiguration).isTrue()
    }

    private fun event(
        type: String,
        detailName: String,
        timestamp: String,
        bounceType: String? = null,
    ): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to type,
            "mail" to mapOf(
                "timestamp" to NOW.minusSeconds(5).toString(),
                "messageId" to "ses-message-123",
                "tags" to mapOf("delivery_id" to listOf(DELIVERY_ID.toString())),
            ),
            detailName to buildMap {
                put("timestamp", timestamp)
                bounceType?.let { put("bounceType", it) }
            },
        ),
    )

    private fun envelope(
        message: String,
        messageId: UUID = SNS_MESSAGE_ID,
        certificateUrl: String = "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-0123456789abcdef.pem",
    ): String {
        val canonical = buildString {
            append("Message\n").append(message).append('\n')
            append("MessageId\n").append(messageId).append('\n')
            append("Timestamp\n").append(NOW).append('\n')
            append("TopicArn\n").append(TOPIC_ARN).append('\n')
            append("Type\nNotification\n")
        }
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        return mapper.writeValueAsString(
            mapOf(
                "Type" to "Notification",
                "MessageId" to messageId.toString(),
                "TopicArn" to TOPIC_ARN,
                "Message" to message,
                "Timestamp" to NOW.toString(),
                "SignatureVersion" to "2",
                "Signature" to Base64.getEncoder().encodeToString(signer.sign()),
                "SigningCertURL" to certificateUrl,
            ),
        )
    }

    private fun properties(
        enabled: Boolean = true,
        queueUrl: String = "https://sqs.eu-west-1.amazonaws.com/111122223333/mundia-ses-feedback",
        topicArn: String = TOPIC_ARN,
        maximumMessageBytes: Int = 262_144,
    ) = SesFeedbackProperties(
        enabled = enabled,
        region = "eu-west-1",
        queueUrl = queueUrl,
        topicArn = topicArn,
        waitTime = Duration.ofSeconds(20),
        visibilityTimeout = Duration.ofMinutes(1),
        maximumMessageBytes = maximumMessageBytes,
        maximumMessageAge = Duration.ofDays(14),
        maximumFutureSkew = Duration.ofMinutes(2),
        maximumPollSilence = Duration.ofMinutes(1),
        startupGracePeriod = Duration.ofSeconds(30),
        retryBackoff = Duration.ofSeconds(1),
        maximumConsecutiveFailures = 5,
        callTimeout = Duration.ofSeconds(25),
        attemptTimeout = Duration.ofSeconds(23),
        certificateConnectTimeout = Duration.ofSeconds(2),
        certificateReadTimeout = Duration.ofSeconds(5),
        certificateCacheDuration = Duration.ofHours(1),
        maximumCertificateBytes = 32_768,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-03T10:00:00Z")
        val SNS_MESSAGE_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val ANOTHER_MESSAGE_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000002")
        val THIRD_MESSAGE_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000003")
        val DELIVERY_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000001")
        const val TOPIC_ARN = "arn:aws:sns:eu-west-1:111122223333:mundia-ses-feedback"
    }
}
