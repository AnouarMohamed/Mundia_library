package com.mundiapolis.library.membership

import com.mundiapolis.library.membership.adapter.outbound.events.ProtobufMembershipEventEncoder
import com.mundiapolis.library.membership.config.MembershipOutboxProperties
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityChanged
import com.mundiapolis.library.membership.contract.v1.MemberEligibilityStatus
import com.mundiapolis.library.membership.dto.ClaimedMembershipOutboxEvent
import com.mundiapolis.library.membership.dto.MembershipOutboxContractException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.Duration
import java.util.UUID

class MembershipOutboxContractTest {
    private val encoder = ProtobufMembershipEventEncoder(
        objectMapper = ObjectMapper(),
        schemaSubject = "mundia.membership.member-eligibility-changed.v1",
        maximumEventBytes = 16 * 1024,
    )

    @Test
    fun `encodes the privacy-minimal eligibility contract`() {
        val encoded = encoder.encode(event(payload()))
        val message = MemberEligibilityChanged.parseFrom(encoded.payload)

        assertThat(encoded.key).isEqualTo(MEMBER_ID.toString())
        assertThat(encoded.eventType).isEqualTo(EVENT_TYPE)
        assertThat(encoded.schemaVersion).isEqualTo(1)
        assertThat(message.eventId).isEqualTo(EVENT_ID.toString())
        assertThat(message.memberId).isEqualTo(MEMBER_ID.toString())
        assertThat(message.aggregateVersion).isEqualTo(7)
        assertThat(message.status)
            .isEqualTo(MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_ELIGIBLE)
        assertThat(message.hasReasonCode()).isFalse()
        assertThat(message.occurredAt.seconds).isEqualTo(OCCURRED_AT.epochSecond)
        assertThat(message.occurredAt.nanos).isEqualTo(OCCURRED_AT.nano)
    }

    @Test
    fun `encodes an ineligible reason code`() {
        val encoded = encoder.encode(
            event(payload(status = "INELIGIBLE", reasonCode = "ACCOUNT_NOT_APPROVED")),
        )
        val message = MemberEligibilityChanged.parseFrom(encoded.payload)

        assertThat(message.status)
            .isEqualTo(MemberEligibilityStatus.MEMBER_ELIGIBILITY_STATUS_INELIGIBLE)
        assertThat(message.reasonCode).isEqualTo("ACCOUNT_NOT_APPROVED")
    }

    @Test
    fun `rejects metadata mismatches and profile data`() {
        assertThatThrownBy {
            encoder.encode(event(payload().replace(EVENT_ID.toString(), UUID.randomUUID().toString())))
        }.isInstanceOf(MembershipOutboxContractException::class.java)

        assertThatThrownBy {
            encoder.encode(event(payload().dropLast(1) + ",\"email\":\"member@example.test\"}"))
        }.isInstanceOf(MembershipOutboxContractException::class.java)
    }

    @Test
    fun `rejects eligibility states with inconsistent reason codes`() {
        assertThatThrownBy {
            encoder.encode(event(payload(status = "ELIGIBLE", reasonCode = "ACCOUNT_NOT_APPROVED")))
        }.isInstanceOf(MembershipOutboxContractException::class.java)

        assertThatThrownBy {
            encoder.encode(event(payload(status = "INELIGIBLE")))
        }.isInstanceOf(MembershipOutboxContractException::class.java)
    }

    @Test
    fun `enabled delivery configuration fails closed on insecure broker settings`() {
        assertThat(properties().isSafeConfiguration).isTrue()
        val unsafe = properties().copy(
            kafka = properties().kafka.copy(
                securityProtocol = "SASL_SSL",
                allowInsecureTransport = false,
                saslMechanism = null,
                saslJaasConfig = null,
            ),
        )
        assertThat(unsafe.isSafeConfiguration).isFalse()
    }

    private fun event(payload: String): ClaimedMembershipOutboxEvent = ClaimedMembershipOutboxEvent(
        eventId = EVENT_ID,
        aggregateId = MEMBER_ID,
        aggregateVersion = 7,
        eventType = EVENT_TYPE,
        eventVersion = 1,
        occurredAt = OCCURRED_AT,
        payloadJson = payload,
        deliveryAttempt = 1,
        leaseToken = UUID.fromString("30000000-0000-0000-0000-000000000003"),
    )

    private fun payload(status: String = "ELIGIBLE", reasonCode: String? = null): String {
        val reason = reasonCode?.let { ",\"reasonCode\":\"$it\"" } ?: ""
        return """{"eventId":"$EVENT_ID","eventType":"$EVENT_TYPE","eventVersion":1,"memberId":"$MEMBER_ID","aggregateVersion":7,"status":"$status"$reason,"occurredAt":"$OCCURRED_AT"}"""
    }

    private fun properties() = MembershipOutboxProperties(
        enabled = true,
        instanceId = "membership-test",
        topic = "mundia.membership.events.v1",
        schemaSubject = "mundia.membership.v1.MemberEligibilityChanged",
        pollInterval = Duration.ofMillis(500),
        leaseDuration = Duration.ofSeconds(90),
        batchSize = 10,
        maximumAttempts = 20,
        retryBaseDelay = Duration.ofSeconds(1),
        retryMaximumDelay = Duration.ofMinutes(5),
        publishedRetention = Duration.ofDays(30),
        cleanupInterval = Duration.ofHours(1),
        cleanupBatchSize = 1_000,
        maximumEventBytes = 262_144,
        maximumPendingAge = Duration.ofMinutes(5),
        kafka = MembershipOutboxProperties.KafkaProperties(
            bootstrapServers = listOf("localhost:9092"),
            securityProtocol = "PLAINTEXT",
            allowInsecureTransport = true,
            saslMechanism = null,
            saslJaasConfig = null,
            truststoreLocation = null,
            truststorePassword = null,
            keystoreLocation = null,
            keystorePassword = null,
            keyPassword = null,
            deliveryTimeout = Duration.ofSeconds(5),
            requestTimeout = Duration.ofSeconds(3),
            maximumBlock = Duration.ofSeconds(1),
        ),
    )

    private companion object {
        val EVENT_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val MEMBER_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val OCCURRED_AT: Instant = Instant.parse("2026-09-27T12:00:00.123456Z")
        const val EVENT_TYPE = "membership.member.eligibility-changed"
    }
}
