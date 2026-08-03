package com.mundiapolis.library.circulation.adapter.outbound.events

import com.mundiapolis.library.circulation.application.model.ClaimedOutboxEvent
import com.mundiapolis.library.circulation.config.OutboxDeliveryProperties
import com.mundiapolis.library.circulation.contract.v1.CirculationEvent
import com.mundiapolis.library.circulation.contract.v1.CopyStatus
import com.mundiapolis.library.circulation.contract.v1.FineStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ProtobufOutboxEventEncoderTest {
    private val encoder = ProtobufOutboxEventEncoder(ObjectMapper(), properties())

    @Test
    fun `copy inventory event is encoded by the immutable v1 broker contract`() {
        val copyId = UUID.randomUUID()
        val event = claimed(
            aggregateType = "copy",
            aggregateId = copyId,
            aggregateVersion = 2,
            eventType = "circulation.copy.condition-changed",
            payload = mapOf(
                "copyId" to copyId.toString(),
                "editionId" to UUID.randomUUID().toString(),
                "branchId" to UUID.randomUUID().toString(),
                "barcode" to "COPY-00001",
                "status" to "DAMAGED",
                "shelfLocation" to null,
                "copyVersion" to 2,
                "actorFingerprint" to "a".repeat(64),
                "reason" to "Binding damage verified by librarian",
            ),
        )

        val envelope = CirculationEvent.parseFrom(encoder.encode(event).payload)

        assertThat(envelope.hasCopy()).isTrue()
        assertThat(envelope.copy.copyId).isEqualTo(copyId.toString())
        assertThat(envelope.copy.status).isEqualTo(CopyStatus.COPY_STATUS_DAMAGED)
        assertThat(envelope.copy.copyVersion).isEqualTo(2)
        assertThat(envelope.copy.reason).isEqualTo("Binding damage verified by librarian")
        assertThat(envelope.copy.hasShelfLocation()).isFalse()
    }

    @Test
    fun `settled fine state remains encodable after the last payment`() {
        val fineId = UUID.randomUUID()
        val event = claimed(
            aggregateType = "fine",
            aggregateId = fineId,
            aggregateVersion = 1,
            eventType = "circulation.fine.payment-recorded",
            payload = mapOf(
                "fineId" to fineId.toString(),
                "loanId" to UUID.randomUUID().toString(),
                "memberId" to UUID.randomUUID().toString(),
                "currency" to "MAD",
                "balanceMinor" to 0,
                "status" to "SETTLED",
                "fineVersion" to 1,
                "ledgerEntryId" to UUID.randomUUID().toString(),
                "ledgerEntryType" to "PAYMENT",
                "ledgerDeltaMinor" to -500,
                "actorFingerprint" to "b".repeat(64),
                "externalReference" to "PAY-00000001",
                "occurredAt" to Instant.parse("2026-08-03T12:00:00Z").toString(),
            ),
        )

        val envelope = CirculationEvent.parseFrom(encoder.encode(event).payload)

        assertThat(envelope.fine.status).isEqualTo(FineStatus.FINE_STATUS_SETTLED)
        assertThat(envelope.fine.balanceMinor).isZero()
    }

    @Test
    fun `copy event type and state combinations fail closed`() {
        val copyId = UUID.randomUUID()
        val event = claimed(
            aggregateType = "copy",
            aggregateId = copyId,
            aggregateVersion = 0,
            eventType = "circulation.copy.registered",
            payload = mapOf(
                "copyId" to copyId.toString(),
                "editionId" to UUID.randomUUID().toString(),
                "branchId" to UUID.randomUUID().toString(),
                "barcode" to "COPY-00002",
                "status" to "WITHDRAWN",
                "shelfLocation" to null,
                "copyVersion" to 0,
                "actorFingerprint" to "c".repeat(64),
                "reason" to "Invalid registration state",
            ),
        )

        assertThatThrownBy { encoder.encode(event) }
            .isInstanceOf(OutboxContractException::class.java)
    }

    private fun claimed(
        aggregateType: String,
        aggregateId: UUID,
        aggregateVersion: Long,
        eventType: String,
        payload: Map<String, Any?>,
    ): ClaimedOutboxEvent {
        val occurredAt = Instant.parse("2026-08-03T12:00:00Z")
        return ClaimedOutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            aggregateVersion = aggregateVersion,
            eventType = eventType,
            eventVersion = 1,
            occurredAt = occurredAt,
            traceId = null,
            payloadJson = ObjectMapper().writeValueAsString(payload),
            createdAt = occurredAt,
            deliveryAttempt = 1,
            leaseToken = UUID.randomUUID(),
        )
    }

    private fun properties(): OutboxDeliveryProperties = OutboxDeliveryProperties(
        enabled = false,
        instanceId = "test-instance",
        topic = "mundia.circulation.events.v1",
        schemaSubject = "mundia.circulation.v1.CirculationEvent",
        pollInterval = Duration.ofMillis(500),
        leaseDuration = Duration.ofSeconds(30),
        batchSize = 10,
        maximumAttempts = 20,
        retryBaseDelay = Duration.ofSeconds(1),
        retryMaximumDelay = Duration.ofMinutes(5),
        publishedRetention = Duration.ofDays(30),
        cleanupInterval = Duration.ofHours(1),
        cleanupBatchSize = 1_000,
        maximumEventBytes = 262_144,
        maximumPendingAge = Duration.ofMinutes(5),
        kafka = OutboxDeliveryProperties.KafkaProperties(
            bootstrapServers = listOf("broker:9092"),
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
}
