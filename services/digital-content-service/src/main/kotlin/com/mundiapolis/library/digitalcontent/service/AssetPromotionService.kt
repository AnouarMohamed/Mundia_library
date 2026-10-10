package com.mundiapolis.library.digitalcontent.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class PromotionClaim(
    val ingestionId: UUID,
    val assetId: UUID,
    val leaseToken: UUID,
    val attempt: Int,
    val sourceObjectKey: String,
    val sourceObjectVersionId: String,
    val sourceObjectEtag: String,
    val destinationObjectKey: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class PromotedObject(
    val objectVersionId: String,
    val objectEtag: String,
    val checksumSha256: String,
    val sizeBytes: Long,
    val mediaType: String,
)

interface PromotionStore {
    fun claim(now: Instant, leaseDuration: Duration): PromotionClaim?

    fun complete(claim: PromotionClaim, promoted: PromotedObject, completedAt: Instant)

    fun retry(claim: PromotionClaim, errorCode: String, retryAt: Instant, failedAt: Instant)

    fun block(claim: PromotionClaim, errorCode: String, failedAt: Instant)
}

fun interface ObjectPromoter {
    fun promote(claim: PromotionClaim): PromotedObject
}

class AssetPromotionService(
    private val store: PromotionStore,
    private val promoter: ObjectPromoter,
    private val clock: Clock,
    private val leaseDuration: Duration,
    private val maximumAttempts: Int,
    private val initialBackoff: Duration,
    private val maximumBackoff: Duration,
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        require(maximumAttempts in 1..20)
        require(!initialBackoff.isNegative && !initialBackoff.isZero)
        require(maximumBackoff >= initialBackoff)
    }

    fun runOnce(): Boolean {
        val claim = store.claim(clock.instant(), leaseDuration) ?: return false
        try {
            store.complete(claim, promoter.promote(claim), clock.instant())
        } catch (failure: PromotionLeaseLostException) {
            throw failure
        } catch (_: PromotionConflictException) {
            store.block(claim, "PROMOTION_CONFLICT", clock.instant())
        } catch (failure: PermanentPromotionException) {
            store.block(claim, failure.errorCode, clock.instant())
        } catch (failure: Exception) {
            val now = clock.instant()
            val errorCode = (failure as? RetryablePromotionException)?.errorCode ?: "UNEXPECTED_FAILURE"
            if (claim.attempt >= maximumAttempts) {
                store.block(claim, errorCode, now)
            } else {
                store.retry(claim, errorCode, now.plus(backoff(claim.attempt)), now)
            }
        }
        return true
    }

    private fun backoff(attempt: Int): Duration {
        var delay = initialBackoff
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = delay.multipliedBy(2).coerceAtMost(maximumBackoff)
        }
        return delay
    }
}

class RetryablePromotionException(val errorCode: String) : RuntimeException() {
    init {
        require(ERROR_CODE.matches(errorCode))
    }
}

class PermanentPromotionException(val errorCode: String) : RuntimeException() {
    init {
        require(ERROR_CODE.matches(errorCode))
    }
}

class PromotionLeaseLostException : RuntimeException()
class PromotionConflictException : RuntimeException()

private fun Duration.coerceAtMost(maximum: Duration): Duration = if (this > maximum) maximum else this

private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{1,63}$")
