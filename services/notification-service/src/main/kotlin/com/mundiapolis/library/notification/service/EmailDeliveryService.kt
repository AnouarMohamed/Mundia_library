package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.config.EmailDeliveryProperties
import com.mundiapolis.library.notification.dto.ClaimedEmailDelivery
import com.mundiapolis.library.notification.dto.EmailDeliveryCycle
import com.mundiapolis.library.notification.dto.EmailDeliveryException
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.mundiapolis.library.notification.dto.EmailProviderReceipt
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class EmailDeliveryService(
    private val store: EmailDeliveryStore,
    private val recipientResolver: NotificationRecipientResolver,
    private val provider: EmailProvider,
    private val executor: ExecutorService,
    private val clock: Clock,
    private val properties: EmailDeliveryProperties,
) {
    fun deliverBatch(): EmailDeliveryCycle {
        val claimedAt = clock.instant()
        val deliveries = store.claimBatch(
            properties.instanceId,
            UUID.randomUUID(),
            claimedAt,
            claimedAt.plus(properties.leaseDuration),
            properties.batchSize,
            properties.maximumAttempts,
        )
        var delivered = 0
        var retryScheduled = 0
        var deadLettered = 0
        var claimLost = 0
        deliveries.forEach { delivery ->
            val outcome = deliverOne(delivery)
            when (outcome) {
                Outcome.DELIVERED -> delivered++
                Outcome.RETRY_SCHEDULED -> retryScheduled++
                Outcome.DEAD_LETTERED -> deadLettered++
                Outcome.CLAIM_LOST -> claimLost++
            }
        }
        return EmailDeliveryCycle(deliveries.size, delivered, retryScheduled, deadLettered, claimLost)
    }

    private fun deliverOne(delivery: ClaimedEmailDelivery): Outcome {
        return try {
            val future = executor.submit<EmailProviderReceipt> {
                val recipient = recipientResolver.resolve(delivery.memberId)
                validateRecipient(recipient.address)
                provider.send(recipient, delivery.subject, delivery.body, delivery.deliveryId)
            }
            val receipt = try {
                future.get(properties.operationTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE)
            } catch (_: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE)
            } catch (exception: ExecutionException) {
                val cause = exception.cause
                if (cause is EmailDeliveryException) throw cause
                throw EmailDeliveryException(EmailDeliveryFailureCode.INTERNAL)
            }
            validateReceipt(receipt)
            if (store.markDelivered(properties.instanceId, delivery, receipt, clock.instant())) {
                Outcome.DELIVERED
            } else {
                Outcome.CLAIM_LOST
            }
        } catch (exception: EmailDeliveryException) {
            recordFailure(delivery, exception.failureCode)
        } catch (_: Exception) {
            recordFailure(delivery, EmailDeliveryFailureCode.INTERNAL)
        }
    }

    private fun recordFailure(delivery: ClaimedEmailDelivery, failureCode: EmailDeliveryFailureCode): Outcome {
        val failedAt = clock.instant()
        val terminal = !failureCode.retryable || delivery.attempt >= properties.maximumAttempts
        val nextAttemptAt = if (terminal) null else failedAt.plus(retryDelay(delivery))
        val recorded = store.recordFailure(
            properties.instanceId,
            delivery,
            failureCode,
            failedAt,
            nextAttemptAt,
            terminal,
        )
        if (!recorded) return Outcome.CLAIM_LOST
        return if (terminal) Outcome.DEAD_LETTERED else Outcome.RETRY_SCHEDULED
    }

    private fun retryDelay(delivery: ClaimedEmailDelivery): Duration {
        val exponent = (delivery.attempt - 1).coerceIn(0, 30)
        var base = properties.retryBaseDelay
        repeat(exponent) {
            base = base.multipliedBy(2).coerceAtMost(properties.retryMaximumDelay)
        }
        val headroom = properties.retryMaximumDelay.minus(base)
        if (headroom.isZero || headroom.isNegative) return base
        val entropy = delivery.deliveryId.mostSignificantBits xor
            delivery.deliveryId.leastSignificantBits xor delivery.attempt.toLong()
        val jitterPermille = Math.floorMod(entropy, 251L)
        return base.plus(base.multipliedBy(jitterPermille).dividedBy(1000).coerceAtMost(headroom))
    }

    private fun validateRecipient(address: String) {
        if (address.length !in 3..320 || address.any(Char::isISOControl) || !EMAIL.matches(address)) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_NOT_FOUND)
        }
    }

    private fun validateReceipt(receipt: EmailProviderReceipt) {
        if (!PROVIDER.matches(receipt.provider) || receipt.messageReference.length !in 1..200 ||
            receipt.messageReference.isBlank() ||
            receipt.messageReference.any(Char::isISOControl)
        ) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_REJECTED)
        }
    }

    private enum class Outcome { DELIVERED, RETRY_SCHEDULED, DEAD_LETTERED, CLAIM_LOST }

    private companion object {
        val PROVIDER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
