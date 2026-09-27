package com.mundiapolis.library.membership

import com.mundiapolis.library.membership.config.MembershipOutboxProperties
import com.mundiapolis.library.membership.dto.ClaimedMembershipOutboxEvent
import com.mundiapolis.library.membership.dto.EncodedMembershipEvent
import com.mundiapolis.library.membership.dto.MembershipBrokerAcknowledgement
import com.mundiapolis.library.membership.dto.MembershipBrokerPublishException
import com.mundiapolis.library.membership.dto.MembershipOutboxContractException
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureCode
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureDisposition
import com.mundiapolis.library.membership.dto.MembershipOutboxStatistics
import com.mundiapolis.library.membership.service.MembershipBrokerPublisher
import com.mundiapolis.library.membership.service.MembershipEventEncoder
import com.mundiapolis.library.membership.service.MembershipOutboxDeliveryService
import com.mundiapolis.library.membership.service.MembershipOutboxStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class MembershipOutboxDeliveryServiceTest {
    @Test
    fun `publishes and records broker coordinates after acknowledgement`() {
        val store = RecordingStore()
        val service = service(store, MembershipBrokerPublisher {
            MembershipBrokerAcknowledgement("mundia.membership.events.v1", 3, 42)
        })

        val cycle = service.deliverBatch()

        assertThat(cycle.published).isEqualTo(1)
        assertThat(cycle.retryScheduled).isZero()
        assertThat(store.acknowledgement)
            .isEqualTo(MembershipBrokerAcknowledgement("mundia.membership.events.v1", 3, 42))
    }

    @Test
    fun `schedules transient failures with bounded exponential delay`() {
        val event = EVENT.copy(deliveryAttempt = 3)
        val store = RecordingStore(event)
        val service = service(store, MembershipBrokerPublisher {
            throw MembershipBrokerPublishException(MembershipOutboxFailureCode.BROKER_TIMEOUT)
        })

        val cycle = service.deliverBatch()

        assertThat(cycle.retryScheduled).isEqualTo(1)
        assertThat(store.failureCode).isEqualTo(MembershipOutboxFailureCode.BROKER_TIMEOUT)
        assertThat(store.nextAttemptAt).isEqualTo(NOW.plusSeconds(4))
    }

    @Test
    fun `blocks invalid contracts without retrying`() {
        val store = RecordingStore()
        val service = MembershipOutboxDeliveryService(
            store,
            MembershipEventEncoder { throw MembershipOutboxContractException("invalid") },
            MembershipBrokerPublisher { error("publisher must not be called") },
            CLOCK,
            properties(),
        )

        val cycle = service.deliverBatch()

        assertThat(cycle.blocked).isEqualTo(1)
        assertThat(store.failureCode).isEqualTo(MembershipOutboxFailureCode.CONTRACT_INVALID)
        assertThat(store.blockImmediately).isTrue()
    }

    private fun service(
        store: MembershipOutboxStore,
        publisher: MembershipBrokerPublisher,
    ) = MembershipOutboxDeliveryService(
        store,
        MembershipEventEncoder {
            EncodedMembershipEvent(
                it.eventId,
                it.aggregateId.toString(),
                it.eventType,
                it.eventVersion,
                "mundia.membership.v1.MemberEligibilityChanged",
                1,
                byteArrayOf(1),
            )
        },
        publisher,
        CLOCK,
        properties(),
    )

    private class RecordingStore(
        private val event: ClaimedMembershipOutboxEvent = EVENT,
    ) : MembershipOutboxStore {
        var acknowledgement: MembershipBrokerAcknowledgement? = null
        var failureCode: MembershipOutboxFailureCode? = null
        var nextAttemptAt: Instant? = null
        var blockImmediately = false

        override fun claimBatch(
            owner: String,
            now: Instant,
            leaseExpiresAt: Instant,
            batchSize: Int,
        ) = listOf(event)

        override fun markPublished(
            owner: String,
            event: ClaimedMembershipOutboxEvent,
            acknowledgement: MembershipBrokerAcknowledgement,
            publishedAt: Instant,
        ): Boolean {
            this.acknowledgement = acknowledgement
            return true
        }

        override fun recordFailure(
            owner: String,
            event: ClaimedMembershipOutboxEvent,
            code: MembershipOutboxFailureCode,
            failedAt: Instant,
            nextAttemptAt: Instant,
            maximumAttempts: Int,
            blockImmediately: Boolean,
        ): MembershipOutboxFailureDisposition {
            failureCode = code
            this.nextAttemptAt = nextAttemptAt
            this.blockImmediately = blockImmediately
            return if (blockImmediately) {
                MembershipOutboxFailureDisposition.BLOCKED
            } else {
                MembershipOutboxFailureDisposition.RETRY_SCHEDULED
            }
        }

        override fun deletePublishedBefore(cutoff: Instant, batchSize: Int) = 0

        override fun statistics(now: Instant) = MembershipOutboxStatistics(0, 0, 0, null)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-27T12:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val EVENT = ClaimedMembershipOutboxEvent(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            UUID.fromString("20000000-0000-0000-0000-000000000002"),
            1,
            "membership.member.eligibility-changed",
            1,
            NOW,
            "{}",
            1,
            UUID.fromString("30000000-0000-0000-0000-000000000003"),
        )

        fun properties() = MembershipOutboxProperties(
            true,
            "membership-test",
            "mundia.membership.events.v1",
            "mundia.membership.v1.MemberEligibilityChanged",
            Duration.ofMillis(500),
            Duration.ofSeconds(90),
            10,
            20,
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            Duration.ofDays(30),
            Duration.ofHours(1),
            1_000,
            262_144,
            Duration.ofMinutes(5),
            MembershipOutboxProperties.KafkaProperties(
                listOf("localhost:9092"),
                "PLAINTEXT",
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(3),
                Duration.ofSeconds(1),
            ),
        )
    }
}
