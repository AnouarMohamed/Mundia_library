package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.config.CatalogOutboxProperties
import com.mundiapolis.library.catalog.dto.CatalogBrokerPublishException
import com.mundiapolis.library.catalog.dto.CatalogOutboxContractException
import com.mundiapolis.library.catalog.dto.CatalogOutboxDeliveryCycle
import com.mundiapolis.library.catalog.dto.CatalogOutboxFailureCode
import com.mundiapolis.library.catalog.dto.CatalogOutboxFailureDisposition
import com.mundiapolis.library.catalog.dto.CatalogOutboxPayloadTooLargeException
import com.mundiapolis.library.catalog.dto.ClaimedCatalogOutboxEvent
import java.time.Clock
import java.time.Duration

class CatalogOutboxDeliveryService(
    private val store: CatalogOutboxStore,
    private val encoder: CatalogEventEncoder,
    private val publisher: CatalogBrokerPublisher,
    private val clock: Clock,
    private val properties: CatalogOutboxProperties,
) {
    fun deliverBatch(): CatalogOutboxDeliveryCycle {
        val claimTime = clock.instant()
        val events = store.claimBatch(
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
        return CatalogOutboxDeliveryCycle(events.size, published, retryScheduled, blocked, claimLost)
    }

    fun cleanupPublished(): Int = store.deletePublishedBefore(
        clock.instant().minus(properties.publishedRetention),
        properties.cleanupBatchSize,
    )

    private fun deliver(event: ClaimedCatalogOutboxEvent): DeliveryOutcome = try {
        val acknowledgement = publisher.publish(encoder.encode(event))
        if (store.markPublished(properties.instanceId, event, acknowledgement, clock.instant())) {
            DeliveryOutcome.PUBLISHED
        } else {
            DeliveryOutcome.CLAIM_LOST
        }
    } catch (_: CatalogOutboxContractException) {
        recordFailure(event, CatalogOutboxFailureCode.CONTRACT_INVALID, blockImmediately = true)
    } catch (_: CatalogOutboxPayloadTooLargeException) {
        recordFailure(event, CatalogOutboxFailureCode.PAYLOAD_TOO_LARGE, blockImmediately = true)
    } catch (exception: CatalogBrokerPublishException) {
        recordFailure(event, exception.failureCode, blockImmediately = false)
    } catch (_: Exception) {
        recordFailure(event, CatalogOutboxFailureCode.BROKER_REJECTED, blockImmediately = false)
    }

    private fun recordFailure(
        event: ClaimedCatalogOutboxEvent,
        code: CatalogOutboxFailureCode,
        blockImmediately: Boolean,
    ): DeliveryOutcome {
        val failedAt = clock.instant()
        return when (
            store.recordFailure(
                owner = properties.instanceId,
                event = event,
                code = code,
                failedAt = failedAt,
                nextAttemptAt = failedAt.plus(retryDelay(event.deliveryAttempt)),
                maximumAttempts = properties.maximumAttempts,
                blockImmediately = blockImmediately,
            )
        ) {
            CatalogOutboxFailureDisposition.RETRY_SCHEDULED -> DeliveryOutcome.RETRY_SCHEDULED
            CatalogOutboxFailureDisposition.BLOCKED -> DeliveryOutcome.BLOCKED
            CatalogOutboxFailureDisposition.CLAIM_LOST -> DeliveryOutcome.CLAIM_LOST
        }
    }

    private fun retryDelay(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceIn(0, MAXIMUM_BACKOFF_EXPONENT)
        val exponential = runCatching {
            properties.retryBaseDelay.multipliedBy(1L shl exponent)
        }.getOrDefault(properties.retryMaximumDelay)
        return exponential.coerceAtMost(properties.retryMaximumDelay)
    }

    private enum class DeliveryOutcome { PUBLISHED, RETRY_SCHEDULED, BLOCKED, CLAIM_LOST }

    private companion object {
        const val MAXIMUM_BACKOFF_EXPONENT = 30
    }
}
