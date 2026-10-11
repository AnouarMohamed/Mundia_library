package com.mundiapolis.library.digitalcontent.service

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.ChecksumAlgorithm
import aws.sdk.kotlin.services.s3.model.ChecksumMode
import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.MetadataDirective
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption
import com.mundiapolis.library.digitalcontent.config.IngestionProperties
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.HexFormat

data class StoredPromotionObject(
    val objectVersionId: String,
    val objectEtag: String,
    val checksumSha256: String,
    val sizeBytes: Long,
    val mediaType: String,
    val kmsKeyId: String,
    val metadataSha256: String,
)

interface PromotionObjectStore {
    fun head(destinationObjectKey: String): StoredPromotionObject?

    fun copyExact(claim: PromotionClaim)
}

class S3ObjectPromoter(
    private val store: PromotionObjectStore,
    private val expectedKmsKeyId: String,
) : ObjectPromoter {
    override fun promote(claim: PromotionClaim): PromotedObject {
        val existing = store.head(claim.destinationObjectKey)
        if (existing != null) return verify(claim, existing)
        try {
            store.copyExact(claim)
        } catch (failure: PermanentPromotionException) {
            throw failure
        } catch (_: Exception) {
            val recovered = store.head(claim.destinationObjectKey)
            if (recovered != null) return verify(claim, recovered)
            throw RetryablePromotionException("S3_COPY_FAILED")
        }
        val promoted = store.head(claim.destinationObjectKey)
            ?: throw RetryablePromotionException("S3_DESTINATION_NOT_VISIBLE")
        return verify(claim, promoted)
    }

    private fun verify(claim: PromotionClaim, stored: StoredPromotionObject): PromotedObject {
        if (stored.checksumSha256 != claim.sha256 || stored.metadataSha256 != claim.sha256 ||
            stored.sizeBytes != claim.sizeBytes || stored.mediaType != claim.mediaType ||
            stored.kmsKeyId != expectedKmsKeyId || stored.objectVersionId.length !in 1..1024 ||
            stored.objectEtag.length !in 1..128 || stored.objectVersionId.any(Char::isISOControl) ||
            stored.objectEtag.any(Char::isISOControl)
        ) {
            throw PermanentPromotionException("DESTINATION_MISMATCH")
        }
        return PromotedObject(
            objectVersionId = stored.objectVersionId,
            objectEtag = stored.objectEtag,
            checksumSha256 = stored.checksumSha256,
            sizeBytes = stored.sizeBytes,
            mediaType = stored.mediaType,
        )
    }
}

class AwsS3PromotionObjectStore(
    private val client: S3Client,
    private val properties: IngestionProperties,
) : PromotionObjectStore {
    override fun head(destinationObjectKey: String): StoredPromotionObject? = runBlocking {
        val response = try {
            client.headObject(HeadObjectRequest {
                bucket = properties.bucket
                key = destinationObjectKey
                checksumMode = ChecksumMode.Enabled
                expectedBucketOwner = properties.expectedBucketOwner
            })
        } catch (_: NoSuchKey) {
            return@runBlocking null
        } catch (_: NotFound) {
            return@runBlocking null
        } catch (_: S3Exception) {
            throw RetryablePromotionException("S3_HEAD_FAILED")
        }
        val checksum = response.checksumSha256?.decodeSha256().orEmpty()
        StoredPromotionObject(
            objectVersionId = response.versionId.orEmpty(),
            objectEtag = response.eTag.orEmpty(),
            checksumSha256 = checksum,
            sizeBytes = response.contentLength ?: -1,
            mediaType = response.contentType.orEmpty(),
            kmsKeyId = if (response.serverSideEncryption == ServerSideEncryption.AwsKms) {
                response.ssekmsKeyId.orEmpty()
            } else {
                ""
            },
            metadataSha256 = response.metadata?.get(METADATA_SHA256).orEmpty(),
        )
    }

    override fun copyExact(claim: PromotionClaim): Unit = runBlocking {
        try {
            client.copyObject(CopyObjectRequest {
                bucket = properties.bucket
                key = claim.destinationObjectKey
                copySource = source(claim)
                copySourceIfMatch = quotedEtag(claim.sourceObjectEtag)
                expectedBucketOwner = properties.expectedBucketOwner
                expectedSourceBucketOwner = properties.expectedBucketOwner
                ifNoneMatch = "*"
                contentType = claim.mediaType
                checksumAlgorithm = ChecksumAlgorithm.Sha256
                metadataDirective = MetadataDirective.Replace
                metadata = mapOf(METADATA_SHA256 to claim.sha256)
                serverSideEncryption = ServerSideEncryption.AwsKms
                ssekmsKeyId = properties.kmsKeyId
            })
        } catch (_: NoSuchKey) {
            throw PermanentPromotionException("SOURCE_OBJECT_MISSING")
        } catch (_: S3Exception) {
            throw RetryablePromotionException("S3_COPY_FAILED")
        }
    }

    private fun source(claim: PromotionClaim): String =
        "${properties.bucket}/${claim.sourceObjectKey}?versionId=${encode(claim.sourceObjectVersionId)}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun quotedEtag(value: String): String {
        val unquoted = when {
            value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1)
            value.startsWith('"') || value.endsWith('"') -> throw PermanentPromotionException("SOURCE_IDENTITY_INVALID")
            else -> value
        }
        if (!ETAG.matches(unquoted)) throw PermanentPromotionException("SOURCE_IDENTITY_INVALID")
        return "\"$unquoted\""
    }

    private fun String.decodeSha256(): String = try {
        HexFormat.of().formatHex(Base64.getDecoder().decode(this)).takeIf { it.length == 64 }.orEmpty()
    } catch (_: IllegalArgumentException) {
        ""
    }

    private companion object {
        const val METADATA_SHA256 = "mundia-sha256"
        val ETAG = Regex("^[0-9a-fA-F]{32}(?:-[1-9][0-9]{0,5})?$")
    }
}
