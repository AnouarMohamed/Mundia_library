package com.mundiapolis.library.digitalcontent

import com.mundiapolis.library.digitalcontent.service.PermanentPromotionException
import com.mundiapolis.library.digitalcontent.service.PromotionClaim
import com.mundiapolis.library.digitalcontent.service.PromotionObjectStore
import com.mundiapolis.library.digitalcontent.service.RetryablePromotionException
import com.mundiapolis.library.digitalcontent.service.S3ObjectPromoter
import com.mundiapolis.library.digitalcontent.service.StoredPromotionObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class S3ObjectPromoterTest {
    @Test
    fun `existing exact destination completes without another copy`() {
        val store = FakeObjectStore(existing = EXACT)
        val result = S3ObjectPromoter(store, KMS_KEY).promote(CLAIM)

        assertEquals(0, store.copyCount)
        assertEquals(CLAIM.sha256, result.checksumSha256)
        assertEquals(EXACT.objectVersionId, result.objectVersionId)
    }

    @Test
    fun `missing destination is copied once and then verified`() {
        val store = FakeObjectStore(afterCopy = EXACT)
        val result = S3ObjectPromoter(store, KMS_KEY).promote(CLAIM)

        assertEquals(1, store.copyCount)
        assertEquals(EXACT.objectEtag, result.objectEtag)
    }

    @Test
    fun `ambiguous copy failure recovers from the exact destination`() {
        val store = FakeObjectStore(afterCopy = EXACT, failAfterCopy = true)
        val result = S3ObjectPromoter(store, KMS_KEY).promote(CLAIM)

        assertEquals(1, store.copyCount)
        assertEquals(EXACT.objectVersionId, result.objectVersionId)
    }

    @Test
    fun `mismatched existing destination blocks instead of overwriting`() {
        val store = FakeObjectStore(existing = EXACT.copy(checksumSha256 = "b".repeat(64)))

        val failure = assertThrows(PermanentPromotionException::class.java) {
            S3ObjectPromoter(store, KMS_KEY).promote(CLAIM)
        }
        assertEquals("DESTINATION_MISMATCH", failure.errorCode)
        assertEquals(0, store.copyCount)
    }

    @Test
    fun `copy failure without destination remains retryable`() {
        val store = FakeObjectStore(failBeforeCopy = true)

        val failure = assertThrows(RetryablePromotionException::class.java) {
            S3ObjectPromoter(store, KMS_KEY).promote(CLAIM)
        }
        assertEquals("S3_COPY_FAILED", failure.errorCode)
    }

    private class FakeObjectStore(
        existing: StoredPromotionObject? = null,
        private val afterCopy: StoredPromotionObject? = null,
        private val failBeforeCopy: Boolean = false,
        private val failAfterCopy: Boolean = false,
    ) : PromotionObjectStore {
        private var current = existing
        var copyCount = 0
            private set

        override fun head(destinationObjectKey: String): StoredPromotionObject? = current

        override fun copyExact(claim: PromotionClaim) {
            copyCount += 1
            if (failBeforeCopy) throw RetryablePromotionException("S3_COPY_FAILED")
            current = afterCopy
            if (failAfterCopy) throw RetryablePromotionException("S3_COPY_FAILED")
        }
    }

    private companion object {
        const val KMS_KEY = "arn:aws:kms:eu-west-1:123456789012:key/00000000-0000-0000-0000-000000000001"
        val CLAIM = PromotionClaim(
            ingestionId = UUID.fromString("31000000-0000-0000-0000-000000000001"),
            assetId = UUID.fromString("32000000-0000-0000-0000-000000000001"),
            leaseToken = UUID.fromString("33000000-0000-0000-0000-000000000001"),
            attempt = 1,
            sourceObjectKey = "quarantine/digital-content/aa/31000000-0000-0000-0000-000000000001/${"a".repeat(64)}.pdf",
            sourceObjectVersionId = "source-version",
            sourceObjectEtag = "source-etag",
            destinationObjectKey = "digital-content/aa/32000000-0000-0000-0000-000000000001/${"a".repeat(64)}.pdf",
            mediaType = "application/pdf",
            sizeBytes = 4096,
            sha256 = "a".repeat(64),
        )
        val EXACT = StoredPromotionObject(
            objectVersionId = "destination-version",
            objectEtag = "destination-etag",
            checksumSha256 = "a".repeat(64),
            sizeBytes = 4096,
            mediaType = "application/pdf",
            kmsKeyId = KMS_KEY,
            metadataSha256 = "a".repeat(64),
        )
    }
}
