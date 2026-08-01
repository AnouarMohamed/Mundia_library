package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.adapter.outbound.events.OutboxContractException
import com.mundiapolis.library.circulation.application.model.BrokerPublishAcknowledgement
import com.mundiapolis.library.circulation.application.model.BrokerPublishException
import com.mundiapolis.library.circulation.application.model.ClaimedOutboxEvent
import com.mundiapolis.library.circulation.application.model.EncodedOutboxEvent
import com.mundiapolis.library.circulation.application.model.OutboxDeliveryStatistics
import com.mundiapolis.library.circulation.application.model.OutboxFailureCode
import com.mundiapolis.library.circulation.application.model.OutboxFailureDisposition
import com.mundiapolis.library.circulation.application.port.outbound.BrokerEventPublisher
import com.mundiapolis.library.circulation.application.port.outbound.EventContractEncoder
import com.mundiapolis.library.circulation.application.port.outbound.OutboxDeliveryStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.config.OutboxDeliveryProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxDeliveryServiceTest {
    private val now = Instant.parse("2026-08-01T10:00:00Z")
    private val properties = properties()

    @Test
    fun `acknowledged event is marked published with the lease token`() {
        val event = event()
        val store = RecordingStore(listOf(event))
        val service = service(store)

        val result = service.deliverBatch()

        assertThat(result.claimed).isOne()
        assertThat(result.published).isOne()
        assertThat(result.retryScheduled).isZero()
        assertThat(store.published).containsExactly(event)
        assertThat(store.failures).isEmpty()
    }

    @Test
    fun `broker failure releases the lease and schedules bounded retry`() {
        val event = event(deliveryAttempt = 3)
        val store = RecordingStore(listOf(event))
        val service =
            service(store) {
                throw BrokerPublishException(OutboxFailureCode.BROKER_AUTHENTICATION)
            }

        val result = service.deliverBatch()

        assertThat(result.retryScheduled).isOne()
        assertThat(store.failures).hasSize(1)
        assertThat(store.failures.single().code)
            .isEqualTo(OutboxFailureCode.BROKER_AUTHENTICATION)
        assertThat(store.failures.single().nextAttemptAt).isEqualTo(now.plusSeconds(4))
        assertThat(store.failures.single().blockImmediately).isFalse()
    }

    @Test
    fun `invalid contract is blocked without publishing poison data`() {
        val event = event()
        val store = RecordingStore(listOf(event), failureDisposition = OutboxFailureDisposition.BLOCKED)
        val service =
            OutboxDeliveryService(
                store = store,
                encoder = EventContractEncoder { throw OutboxContractException() },
                publisher = BrokerEventPublisher { error("publisher must not be called") },
                timeProvider = TimeProvider { now },
                properties = properties,
            )

        val result = service.deliverBatch()

        assertThat(result.blocked).isOne()
        assertThat(store.failures.single().code)
            .isEqualTo(OutboxFailureCode.CONTRACT_INVALID)
        assertThat(store.failures.single().blockImmediately).isTrue()
    }

    @Test
    fun `lost lease after broker acknowledgement remains unpublished for safe replay`() {
        val event = event()
        val store = RecordingStore(listOf(event), markPublishedResult = false)
        val service = service(store)

        val result = service.deliverBatch()

        assertThat(result.claimLost).isOne()
        assertThat(store.failures).isEmpty()
    }

    @Test
    fun `retention cleanup uses configured cutoff and bounded batch`() {
        val store = RecordingStore(emptyList())
        val service = service(store)

        service.cleanupPublished()

        assertThat(store.cleanupCutoff).isEqualTo(now.minus(Duration.ofDays(30)))
        assertThat(store.cleanupBatchSize).isEqualTo(1000)
    }

    private fun service(
        store: RecordingStore,
        publisher: BrokerEventPublisher =
            BrokerEventPublisher {
                BrokerPublishAcknowledgement(properties.topic, 2, 42)
            },
    ): OutboxDeliveryService =
        OutboxDeliveryService(
            store = store,
            encoder = EventContractEncoder(::encoded),
            publisher = publisher,
            timeProvider = TimeProvider { now },
            properties = properties,
        )

    private fun event(deliveryAttempt: Int = 1): ClaimedOutboxEvent =
        ClaimedOutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "loan",
            aggregateId = UUID.randomUUID(),
            aggregateVersion = 1,
            eventType = "circulation.loan.requested",
            eventVersion = 1,
            occurredAt = now,
            traceId = null,
            payloadJson = "{}",
            createdAt = now.minusSeconds(1),
            deliveryAttempt = deliveryAttempt,
            leaseToken = UUID.randomUUID(),
        )

    private fun encoded(event: ClaimedOutboxEvent): EncodedOutboxEvent =
        EncodedOutboxEvent(
            eventId = event.id,
            key = event.aggregateId.toString(),
            eventType = event.eventType,
            eventVersion = event.eventVersion,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            aggregateVersion = event.aggregateVersion,
            occurredAt = event.occurredAt,
            schemaSubject = properties.schemaSubject,
            schemaVersion = 1,
            payload = byteArrayOf(1, 2, 3),
        )

    private fun properties(): OutboxDeliveryProperties =
        OutboxDeliveryProperties(
            enabled = true,
            instanceId = "test-instance",
            topic = "mundia.circulation.events.v1",
            schemaSubject = "mundia.circulation.v1.CirculationEvent",
            pollInterval = Duration.ofMillis(500),
            leaseDuration = Duration.ofSeconds(30),
            batchSize = 2,
            maximumAttempts = 20,
            retryBaseDelay = Duration.ofSeconds(1),
            retryMaximumDelay = Duration.ofMinutes(5),
            publishedRetention = Duration.ofDays(30),
            cleanupInterval = Duration.ofHours(1),
            cleanupBatchSize = 1000,
            maximumEventBytes = 262_144,
            maximumPendingAge = Duration.ofMinutes(5),
            kafka =
                OutboxDeliveryProperties.KafkaProperties(
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

    private class RecordingStore(
        private val claims: List<ClaimedOutboxEvent>,
        private val failureDisposition: OutboxFailureDisposition =
            OutboxFailureDisposition.RETRY_SCHEDULED,
        private val markPublishedResult: Boolean = true,
    ) : OutboxDeliveryStore {
        val published = mutableListOf<ClaimedOutboxEvent>()
        val failures = mutableListOf<Failure>()
        var cleanupCutoff: Instant? = null
        var cleanupBatchSize: Int? = null

        override fun claimBatch(
            owner: String,
            now: Instant,
            leaseExpiresAt: Instant,
            batchSize: Int,
        ): List<ClaimedOutboxEvent> = claims

        override fun markPublished(
            owner: String,
            event: ClaimedOutboxEvent,
            acknowledgement: BrokerPublishAcknowledgement,
            publishedAt: Instant,
        ): Boolean {
            published += event
            return markPublishedResult
        }

        override fun recordFailure(
            owner: String,
            event: ClaimedOutboxEvent,
            code: OutboxFailureCode,
            failedAt: Instant,
            nextAttemptAt: Instant,
            maximumAttempts: Int,
            blockImmediately: Boolean,
        ): OutboxFailureDisposition {
            failures += Failure(code, nextAttemptAt, blockImmediately)
            return failureDisposition
        }

        override fun deletePublishedBefore(cutoff: Instant, batchSize: Int): Int {
            cleanupCutoff = cutoff
            cleanupBatchSize = batchSize
            return 0
        }

        override fun statistics(now: Instant): OutboxDeliveryStatistics =
            OutboxDeliveryStatistics(0, 0, 0, null)
    }

    private data class Failure(
        val code: OutboxFailureCode,
        val nextAttemptAt: Instant,
        val blockImmediately: Boolean,
    )
}
