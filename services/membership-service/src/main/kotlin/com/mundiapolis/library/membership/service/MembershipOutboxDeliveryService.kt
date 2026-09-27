package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.config.MembershipOutboxProperties
import com.mundiapolis.library.membership.dto.ClaimedMembershipOutboxEvent
import com.mundiapolis.library.membership.dto.MembershipBrokerPublishException
import com.mundiapolis.library.membership.dto.MembershipOutboxContractException
import com.mundiapolis.library.membership.dto.MembershipOutboxDeliveryCycle
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureCode
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureDisposition
import com.mundiapolis.library.membership.dto.MembershipOutboxPayloadTooLargeException
import java.time.Clock
import java.time.Duration

class MembershipOutboxDeliveryService(
    private val store: MembershipOutboxStore,
    private val encoder: MembershipEventEncoder,
    private val publisher: MembershipBrokerPublisher,
    private val clock: Clock,
    private val properties: MembershipOutboxProperties,
) {
    fun deliverBatch(): MembershipOutboxDeliveryCycle {
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
        return MembershipOutboxDeliveryCycle(events.size, published, retryScheduled, blocked, claimLost)
    }

    fun cleanupPublished(): Int = store.deletePublishedBefore(
        clock.instant().minus(properties.publishedRetention),
        properties.cleanupBatchSize,
    )

    private fun deliver(event: ClaimedMembershipOutboxEvent): DeliveryOutcome = try {
        val acknowledgement = publisher.publish(encoder.encode(event))
        if (store.markPublished(properties.instanceId, event, acknowledgement, clock.instant())) {
            DeliveryOutcome.PUBLISHED
        } else {
            DeliveryOutcome.CLAIM_LOST
        }
    } catch (_: MembershipOutboxContractException) {
        recordFailure(event, MembershipOutboxFailureCode.CONTRACT_INVALID, blockImmediately = true)
    } catch (_: MembershipOutboxPayloadTooLargeException) {
        recordFailure(event, MembershipOutboxFailureCode.PAYLOAD_TOO_LARGE, blockImmediately = true)
    } catch (exception: MembershipBrokerPublishException) {
        recordFailure(event, exception.failureCode, blockImmediately = false)
    } catch (_: Exception) {
        recordFailure(event, MembershipOutboxFailureCode.BROKER_REJECTED, blockImmediately = false)
    }

    private fun recordFailure(
        event: ClaimedMembershipOutboxEvent,
        code: MembershipOutboxFailureCode,
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
            MembershipOutboxFailureDisposition.RETRY_SCHEDULED -> DeliveryOutcome.RETRY_SCHEDULED
            MembershipOutboxFailureDisposition.BLOCKED -> DeliveryOutcome.BLOCKED
            MembershipOutboxFailureDisposition.CLAIM_LOST -> DeliveryOutcome.CLAIM_LOST
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
