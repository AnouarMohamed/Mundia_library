package com.mundiapolis.library.catalog

import com.google.protobuf.Timestamp
import com.mundiapolis.library.catalog.adapter.`in`.events.CirculationEventContractException
import com.mundiapolis.library.catalog.adapter.`in`.events.CirculationEventRecordDecoder
import com.mundiapolis.library.catalog.config.CirculationConsumerProperties
import com.mundiapolis.library.catalog.dto.DecodedCirculationRecord
import com.mundiapolis.library.catalog.dto.ProjectedCopyStatus
import com.mundiapolis.library.circulation.contract.v1.CirculationEvent
import com.mundiapolis.library.circulation.contract.v1.CopyEvent
import com.mundiapolis.library.circulation.contract.v1.CopyStatus
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CirculationEventRecordDecoderTest {
    private val properties = properties()
    private val decoder = CirculationEventRecordDecoder(properties)

    @Test
    fun `decodes authoritative copy state and ignores other circulation aggregates`() {
        val copyId = UUID.randomUUID()
        val editionId = UUID.randomUUID()
        val copyEnvelope = envelope(
            aggregateId = copyId,
            aggregateType = "copy",
            eventType = "circulation.copy.status-changed",
            aggregateVersion = 4,
        ).toBuilder()
            .setCopy(
                CopyEvent.newBuilder()
                    .setCopyId(copyId.toString())
                    .setEditionId(editionId.toString())
                    .setBranchId(UUID.randomUUID().toString())
                    .setBarcode("COPY-100")
                    .setStatus(CopyStatus.COPY_STATUS_RESERVED)
                    .setCopyVersion(4)
                    .setReason("Copy assigned to waiting reservation"),
            )
            .build()

        val decoded = decoder.decode(record(copyEnvelope)) as DecodedCirculationRecord.Copy

        assertThat(decoded.event.copyId).isEqualTo(copyId)
        assertThat(decoded.event.editionId).isEqualTo(editionId)
        assertThat(decoded.event.aggregateVersion).isEqualTo(4)
        assertThat(decoded.event.status).isEqualTo(ProjectedCopyStatus.RESERVED)
        assertThat(decoded.event.payloadSha256).matches("[0-9a-f]{64}")

        val loanId = UUID.randomUUID()
        val loanEnvelope = envelope(
            aggregateId = loanId,
            aggregateType = "loan",
            eventType = "circulation.loan.requested",
            aggregateVersion = 0,
        )
        assertThat(decoder.decode(record(loanEnvelope))).isEqualTo(DecodedCirculationRecord.Ignored)
    }

    @Test
    fun `rejects duplicate security-sensitive headers`() {
        val aggregateId = UUID.randomUUID()
        val envelope = envelope(
            aggregateId = aggregateId,
            aggregateType = "loan",
            eventType = "circulation.loan.requested",
            aggregateVersion = 0,
        )
        val record = record(envelope)
        record.headers().add("event-id", envelope.eventId.toByteArray(StandardCharsets.UTF_8))

        assertThatThrownBy { decoder.decode(record) }
            .isInstanceOf(CirculationEventContractException::class.java)
    }

    private fun envelope(
        aggregateId: UUID,
        aggregateType: String,
        eventType: String,
        aggregateVersion: Long,
    ): CirculationEvent = CirculationEvent.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setEventType(eventType)
        .setEventVersion(1)
        .setAggregateType(aggregateType)
        .setAggregateId(aggregateId.toString())
        .setAggregateVersion(aggregateVersion)
        .setOccurredAt(
            Timestamp.newBuilder()
                .setSeconds(Instant.parse("2026-09-25T10:00:00Z").epochSecond),
        )
        .build()

    private fun record(envelope: CirculationEvent): ConsumerRecord<String, ByteArray> {
        val record = ConsumerRecord(
            properties.topic,
            0,
            10,
            envelope.aggregateId,
            envelope.toByteArray(),
        )
        fun header(name: String, value: String) {
            record.headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
        }
        header("content-type", "application/x-protobuf")
        header("event-id", envelope.eventId)
        header("event-type", envelope.eventType)
        header("event-version", envelope.eventVersion.toString())
        header("schema-subject", properties.schemaSubject)
        header("schema-version", properties.schemaVersion.toString())
        return record
    }

    private fun properties() = CirculationConsumerProperties(
        enabled = true,
        instanceId = "catalog-test",
        groupId = "catalog-test",
        topic = "mundia.circulation.events.v1",
        schemaSubject = "mundia.circulation.v1.CirculationEvent",
        schemaVersion = 1,
        pollTimeout = Duration.ofMillis(500),
        commitTimeout = Duration.ofSeconds(5),
        retryBackoff = Duration.ofMillis(100),
        startupGracePeriod = Duration.ofSeconds(30),
        maximumPollSilence = Duration.ofSeconds(30),
        maximumPollRecords = 10,
        maximumEventBytes = 262_144,
        kafka = CirculationConsumerProperties.KafkaProperties(
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
            requestTimeout = Duration.ofSeconds(10),
            sessionTimeout = Duration.ofSeconds(30),
            heartbeatInterval = Duration.ofSeconds(3),
        ),
    )
}
