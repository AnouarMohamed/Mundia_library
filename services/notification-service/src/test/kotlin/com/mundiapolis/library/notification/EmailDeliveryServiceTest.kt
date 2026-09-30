package com.mundiapolis.library.notification

import com.mundiapolis.library.notification.config.EmailDeliveryProperties
import com.mundiapolis.library.notification.dto.ClaimedEmailDelivery
import com.mundiapolis.library.notification.dto.EmailDeliveryException
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.mundiapolis.library.notification.dto.EmailDeliveryStatistics
import com.mundiapolis.library.notification.dto.EmailProviderReceipt
import com.mundiapolis.library.notification.dto.EmailRecipient
import com.mundiapolis.library.notification.service.EmailDeliveryService
import com.mundiapolis.library.notification.service.EmailDeliveryStore
import com.mundiapolis.library.notification.service.EmailProvider
import com.mundiapolis.library.notification.service.NotificationRecipientResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

class EmailDeliveryServiceTest {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun closeExecutor() = executor.close()

    @Test
    fun `successful delivery passes the durable delivery id to the provider`() {
        val store = FakeStore()
        var providerKey: UUID? = null
        val service = service(store) { _, _, _, deliveryId ->
            providerKey = deliveryId
            EmailProviderReceipt("brevo", "message-123")
        }

        val result = service.deliverBatch()

        assertThat(result.delivered).isEqualTo(1)
        assertThat(providerKey).isEqualTo(DELIVERY.deliveryId)
        assertThat(store.deliveredReceipt).isEqualTo(EmailProviderReceipt("brevo", "message-123"))
        assertThat(store.failure).isNull()
    }

    @Test
    fun `retryable provider failure is rescheduled with bounded backoff`() {
        val store = FakeStore()
        val service = service(store) { _, _, _, _ ->
            throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_RATE_LIMITED)
        }

        val result = service.deliverBatch()

        assertThat(result.retryScheduled).isEqualTo(1)
        assertThat(store.failure?.code).isEqualTo(EmailDeliveryFailureCode.PROVIDER_RATE_LIMITED)
        assertThat(store.failure?.deadLetter).isFalse()
        assertThat(store.failure?.nextAttemptAt).isBetween(NOW.plusSeconds(5), NOW.plusMillis(6_250))
    }

    @Test
    fun `permanent failure is dead lettered without another attempt`() {
        val store = FakeStore()
        val service = service(store) { _, _, _, _ ->
            throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_REJECTED)
        }

        val result = service.deliverBatch()

        assertThat(result.deadLettered).isEqualTo(1)
        assertThat(store.failure?.deadLetter).isTrue()
        assertThat(store.failure?.nextAttemptAt).isNull()
    }

    @Test
    fun `last retryable attempt is dead lettered`() {
        val store = FakeStore(DELIVERY.copy(attempt = 8))
        val service = service(store) { _, _, _, _ -> throw IllegalStateException("secret provider response") }

        val result = service.deliverBatch()

        assertThat(result.deadLettered).isEqualTo(1)
        assertThat(store.failure?.code).isEqualTo(EmailDeliveryFailureCode.INTERNAL)
        assertThat(store.failure?.deadLetter).isTrue()
    }

    @Test
    fun `stale owner cannot acknowledge a completed delivery`() {
        val store = FakeStore(markDelivered = false)
        val result = service(store) { _, _, _, _ -> EmailProviderReceipt("brevo", "message-123") }
            .deliverBatch()

        assertThat(result.claimLost).isEqualTo(1)
        assertThat(result.delivered).isZero()
    }

    @Test
    fun `unsafe enabled configuration is rejected`() {
        assertThat(properties(leaseDuration = Duration.ofSeconds(30)).isSafeConfiguration).isFalse()
        assertThat(properties().isSafeConfiguration).isTrue()
    }

    private fun service(
        store: FakeStore,
        provider: EmailProvider,
    ) = EmailDeliveryService(
        store,
        NotificationRecipientResolver { EmailRecipient("reader@example.test") },
        provider,
        executor,
        Clock.fixed(NOW, ZoneOffset.UTC),
        properties(),
    )

    private fun properties(leaseDuration: Duration = Duration.ofMinutes(2)) = EmailDeliveryProperties(
        enabled = true,
        instanceId = "worker-1",
        pollInterval = Duration.ofSeconds(1),
        leaseDuration = leaseDuration,
        batchSize = 10,
        maximumAttempts = 8,
        retryBaseDelay = Duration.ofSeconds(5),
        retryMaximumDelay = Duration.ofHours(1),
        operationTimeout = Duration.ofSeconds(10),
        maximumPendingAge = Duration.ofMinutes(5),
    )

    private class FakeStore(
        private val claimed: ClaimedEmailDelivery = DELIVERY,
        private val markDelivered: Boolean = true,
    ) : EmailDeliveryStore {
        var deliveredReceipt: EmailProviderReceipt? = null
        var failure: Failure? = null

        override fun claimBatch(
            owner: String,
            leaseToken: UUID,
            now: Instant,
            leaseExpiresAt: Instant,
            batchSize: Int,
            maximumAttempts: Int,
        ) = listOf(claimed.copy(leaseToken = leaseToken))

        override fun markDelivered(
            owner: String,
            delivery: ClaimedEmailDelivery,
            receipt: EmailProviderReceipt,
            deliveredAt: Instant,
        ): Boolean {
            deliveredReceipt = receipt
            return markDelivered
        }

        override fun recordFailure(
            owner: String,
            delivery: ClaimedEmailDelivery,
            failureCode: EmailDeliveryFailureCode,
            failedAt: Instant,
            nextAttemptAt: Instant?,
            deadLetter: Boolean,
        ): Boolean {
            failure = Failure(failureCode, nextAttemptAt, deadLetter)
            return true
        }

        override fun statistics(now: Instant) = EmailDeliveryStatistics(0, 0, 0, 0, 0, null)
    }

    private data class Failure(
        val code: EmailDeliveryFailureCode,
        val nextAttemptAt: Instant?,
        val deadLetter: Boolean,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-29T10:00:00Z")
        val DELIVERY = ClaimedEmailDelivery(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            UUID.fromString("20000000-0000-4000-8000-000000000001"),
            UUID.fromString("30000000-0000-4000-8000-000000000001"),
            "Loan due soon",
            "Your loan is due soon.",
            1,
            UUID.fromString("40000000-0000-4000-8000-000000000001"),
        )
    }
}
