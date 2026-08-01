package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.adapter.outbound.events.OutboxContractException
import com.mundiapolis.library.circulation.adapter.outbound.events.OutboxPayloadTooLargeException
import com.mundiapolis.library.circulation.application.model.BrokerPublishException
import com.mundiapolis.library.circulation.application.model.ClaimedOutboxEvent
import com.mundiapolis.library.circulation.application.model.OutboxDeliveryCycle
import com.mundiapolis.library.circulation.application.model.OutboxFailureCode
import com.mundiapolis.library.circulation.application.model.OutboxFailureDisposition
import com.mundiapolis.library.circulation.application.port.outbound.BrokerEventPublisher
import com.mundiapolis.library.circulation.application.port.outbound.EventContractEncoder
import com.mundiapolis.library.circulation.application.port.outbound.OutboxDeliveryStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.config.OutboxDeliveryProperties
import java.time.Duration
import java.time.Instant

class OutboxDeliveryService(
    private val store: OutboxDeliveryStore,
    private val encoder: EventContractEncoder,
    private val publisher: BrokerEventPublisher,
    private val timeProvider: TimeProvider,
    private val properties: OutboxDeliveryProperties,
) {
    fun deliverBatch(): OutboxDeliveryCycle {
        val claimTime = timeProvider.now()
        val events =
            store.claimBatch(
                owner = properties.instanceId,
                now = claimTime,
                leaseExpiresAt = claimTime.plus(properties.leaseDuration),
                batchSize = properties.batchSize,
            )
        var published = 0
        var retryScheduled = 0
        var blocked = 0
        var claimLost = 0

        events.forEach { event ->
            when (deliver(event)) {
                DeliveryOutcome.PUBLISHED -> published += 1
                DeliveryOutcome.RETRY_SCHEDULED -> retryScheduled += 1
                DeliveryOutcome.BLOCKED -> blocked += 1
                DeliveryOutcome.CLAIM_LOST -> claimLost += 1
            }
        }

        return OutboxDeliveryCycle(
            claimed = events.size,
            published = published,
            retryScheduled = retryScheduled,
            blocked = blocked,
            claimLost = claimLost,
        )
    }

    fun cleanupPublished(): Int =
        store.deletePublishedBefore(
            cutoff = timeProvider.now().minus(properties.publishedRetention),
            batchSize = properties.cleanupBatchSize,
        )

    private fun deliver(event: ClaimedOutboxEvent): DeliveryOutcome =
        try {
            val encoded = encoder.encode(event)
            val acknowledgement = publisher.publish(encoded)
            val marked =
                store.markPublished(
                    owner = properties.instanceId,
                    event = event,
                    acknowledgement = acknowledgement,
                    publishedAt = timeProvider.now(),
                )
            if (marked) DeliveryOutcome.PUBLISHED else DeliveryOutcome.CLAIM_LOST
        } catch (_: OutboxContractException) {
            recordFailure(event, OutboxFailureCode.CONTRACT_INVALID, blockImmediately = true)
        } catch (_: OutboxPayloadTooLargeException) {
            recordFailure(event, OutboxFailureCode.PAYLOAD_TOO_LARGE, blockImmediately = true)
        } catch (exception: BrokerPublishException) {
            recordFailure(event, exception.failureCode, blockImmediately = false)
        } catch (_: Exception) {
            recordFailure(event, OutboxFailureCode.BROKER_REJECTED, blockImmediately = false)
        }

    private fun recordFailure(
        event: ClaimedOutboxEvent,
        code: OutboxFailureCode,
        blockImmediately: Boolean,
    ): DeliveryOutcome {
        val failedAt = timeProvider.now()
        val disposition =
            store.recordFailure(
                owner = properties.instanceId,
                event = event,
                code = code,
                failedAt = failedAt,
                nextAttemptAt = failedAt.plus(retryDelay(event.deliveryAttempt)),
                maximumAttempts = properties.maximumAttempts,
                blockImmediately = blockImmediately,
            )
        return when (disposition) {
            OutboxFailureDisposition.RETRY_SCHEDULED -> DeliveryOutcome.RETRY_SCHEDULED
            OutboxFailureDisposition.BLOCKED -> DeliveryOutcome.BLOCKED
            OutboxFailureDisposition.CLAIM_LOST -> DeliveryOutcome.CLAIM_LOST
        }
    }

    private fun retryDelay(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceIn(0, MAXIMUM_BACKOFF_EXPONENT)
        val multiplier = 1L shl exponent
        val exponential =
            runCatching { properties.retryBaseDelay.multipliedBy(multiplier) }
                .getOrDefault(properties.retryMaximumDelay)
        return if (exponential > properties.retryMaximumDelay) {
            properties.retryMaximumDelay
        } else {
            exponential
        }
    }

    private enum class DeliveryOutcome {
        PUBLISHED,
        RETRY_SCHEDULED,
        BLOCKED,
        CLAIM_LOST,
    }

    private companion object {
        const val MAXIMUM_BACKOFF_EXPONENT = 30
    }
}
