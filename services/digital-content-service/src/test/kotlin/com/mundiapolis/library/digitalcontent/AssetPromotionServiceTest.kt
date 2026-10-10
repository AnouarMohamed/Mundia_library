package com.mundiapolis.library.digitalcontent

import com.mundiapolis.library.digitalcontent.service.AssetPromotionService
import com.mundiapolis.library.digitalcontent.service.ObjectPromoter
import com.mundiapolis.library.digitalcontent.service.PermanentPromotionException
import com.mundiapolis.library.digitalcontent.service.PromotedObject
import com.mundiapolis.library.digitalcontent.service.PromotionClaim
import com.mundiapolis.library.digitalcontent.service.PromotionStore
import com.mundiapolis.library.digitalcontent.service.RetryablePromotionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AssetPromotionServiceTest {
    @Test
    fun `successful promotion completes the exact leased claim`() {
        val store = RecordingStore(CLAIM)
        val service = service(store, ObjectPromoter { PROMOTED })

        assertTrue(service.runOnce())
        assertEquals(PROMOTED, store.completed)
        assertEquals(null, store.retried)
        assertEquals(null, store.blocked)
    }

    @Test
    fun `retryable failure uses bounded exponential backoff`() {
        val store = RecordingStore(CLAIM.copy(attempt = 3))
        val service = service(store, ObjectPromoter { throw RetryablePromotionException("S3_UNAVAILABLE") })

        assertTrue(service.runOnce())
        assertEquals("S3_UNAVAILABLE", store.retried?.first)
        assertEquals(NOW.plusSeconds(20), store.retried?.second)
    }

    @Test
    fun `permanent and exhausted failures are blocked`() {
        val permanent = RecordingStore(CLAIM)
        service(permanent, ObjectPromoter { throw PermanentPromotionException("OBJECT_MISMATCH") }).runOnce()
        assertEquals("OBJECT_MISMATCH", permanent.blocked)

        val exhausted = RecordingStore(CLAIM.copy(attempt = 5))
        service(exhausted, ObjectPromoter { throw RetryablePromotionException("S3_UNAVAILABLE") }).runOnce()
        assertEquals("S3_UNAVAILABLE", exhausted.blocked)
        assertEquals(null, exhausted.retried)
    }

    @Test
    fun `empty queue performs no object operation`() {
        val store = RecordingStore(null)
        val service = service(store, ObjectPromoter { error("must not run") })

        assertFalse(service.runOnce())
    }

    private fun service(store: RecordingStore, promoter: ObjectPromoter) = AssetPromotionService(
        store = store,
        promoter = promoter,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        leaseDuration = Duration.ofMinutes(2),
        maximumAttempts = 5,
        initialBackoff = Duration.ofSeconds(5),
        maximumBackoff = Duration.ofMinutes(1),
    )

    private class RecordingStore(private val claim: PromotionClaim?) : PromotionStore {
        var completed: PromotedObject? = null
        var retried: Pair<String, Instant>? = null
        var blocked: String? = null

        override fun claim(now: Instant, leaseDuration: Duration): PromotionClaim? = claim

        override fun complete(claim: PromotionClaim, promoted: PromotedObject, completedAt: Instant) {
            completed = promoted
        }

        override fun retry(claim: PromotionClaim, errorCode: String, retryAt: Instant, failedAt: Instant) {
            retried = errorCode to retryAt
        }

        override fun block(claim: PromotionClaim, errorCode: String, failedAt: Instant) {
            blocked = errorCode
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-10T12:00:00Z")
        val CLAIM = PromotionClaim(
            ingestionId = UUID.fromString("21000000-0000-0000-0000-000000000001"),
            assetId = UUID.fromString("22000000-0000-0000-0000-000000000001"),
            leaseToken = UUID.fromString("23000000-0000-0000-0000-000000000001"),
            attempt = 1,
            sourceObjectKey = "quarantine/digital-content/aa/21000000-0000-0000-0000-000000000001/${"a".repeat(64)}.pdf",
            sourceObjectVersionId = "source-version",
            sourceObjectEtag = "source-etag",
            destinationObjectKey = "digital-content/aa/22000000-0000-0000-0000-000000000001/${"a".repeat(64)}.pdf",
            mediaType = "application/pdf",
            sizeBytes = 4096,
            sha256 = "a".repeat(64),
        )
        val PROMOTED = PromotedObject(
            objectVersionId = "destination-version",
            objectEtag = "destination-etag",
            checksumSha256 = "a".repeat(64),
            sizeBytes = 4096,
            mediaType = "application/pdf",
        )
    }
}
