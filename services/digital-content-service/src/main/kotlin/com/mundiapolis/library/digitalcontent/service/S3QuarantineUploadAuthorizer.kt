package com.mundiapolis.library.digitalcontent.service

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import com.mundiapolis.library.digitalcontent.config.IngestionProperties
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.time.toKotlinDuration

class S3QuarantineUploadAuthorizer(
    private val client: S3Client,
    private val properties: IngestionProperties,
) : QuarantineUploadAuthorizer {
    override fun authorize(manifest: IngestionManifest, issuedAt: java.time.Instant): SignedUpload = runBlocking {
        val checksum = Base64.getEncoder().encodeToString(manifest.sha256.hexToBytes())
        val request = PutObjectRequest {
            bucket = properties.bucket
            key = manifest.quarantineObjectKey
            contentLength = manifest.sizeBytes
            contentType = manifest.mediaType
            checksumSha256 = checksum
            expectedBucketOwner = properties.expectedBucketOwner
            serverSideEncryption = ServerSideEncryption.AwsKms
            ssekmsKeyId = properties.kmsKeyId
            ifNoneMatch = "*"
        }
        val presigned = client.presignPutObject(request, properties.uploadUrlLifetime.toKotlinDuration())
        val headers = linkedMapOf<String, String>()
        presigned.headers.forEach { name, values ->
            if (!name.equals("host", ignoreCase = true)) headers[name.lowercase()] = values.joinToString(", ")
        }
        SignedUpload(
            url = presigned.url.toString(),
            headers = headers.toSortedMap(),
            expiresAt = issuedAt.plus(properties.uploadUrlLifetime),
        )
    }

    private fun String.hexToBytes(): ByteArray {
        require(length == 64 && all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
