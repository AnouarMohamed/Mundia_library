package com.mundiapolis.library.digitalcontent

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import com.mundiapolis.library.digitalcontent.config.IngestionProperties
import com.mundiapolis.library.digitalcontent.dto.DigitalFormat
import com.mundiapolis.library.digitalcontent.service.IngestionManifest
import com.mundiapolis.library.digitalcontent.service.S3QuarantineUploadAuthorizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class S3QuarantineUploadAuthorizerTest {
    @Test
    fun `presigned upload is create-only and binds checksum size media type encryption and owner`() {
        val client = S3Client {
            region = "eu-west-1"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "AKIDEXAMPLE"
                secretAccessKey = "secret-example"
            }
        }
        client.use {
            val issuedAt = Instant.parse("2026-10-04T12:00:00Z")
            val signed = S3QuarantineUploadAuthorizer(client, properties()).authorize(manifest(issuedAt), issuedAt)
            assertTrue(signed.url.startsWith("https://mundia-quarantine-prod.s3.eu-west-1.amazonaws.com/"))
            assertTrue(signed.url.contains("X-Amz-Signature="))
            assertFalse(signed.headers.keys.any { it.equals("host", ignoreCase = true) })
            assertEquals("4096", signed.headers["content-length"])
            assertEquals("application/pdf", signed.headers["content-type"])
            assertEquals("*", signed.headers["if-none-match"])
            assertEquals(
                Base64.getEncoder().encodeToString(ByteArray(32) { 0xaa.toByte() }),
                signed.headers["x-amz-checksum-sha256"],
            )
            assertEquals("111122223333", signed.headers["x-amz-expected-bucket-owner"])
            assertEquals("aws:kms", signed.headers["x-amz-server-side-encryption"])
            assertEquals(
                "arn:aws:kms:eu-west-1:111122223333:key/00000000-0000-0000-0000-000000000001",
                signed.headers["x-amz-server-side-encryption-aws-kms-key-id"],
            )
            assertEquals(issuedAt.plusSeconds(300), signed.expiresAt)
        }
    }

    private fun properties() = IngestionProperties(
        enabled = true,
        region = "eu-west-1",
        bucket = "mundia-quarantine-prod",
        expectedBucketOwner = "111122223333",
        kmsKeyId = "arn:aws:kms:eu-west-1:111122223333:key/00000000-0000-0000-0000-000000000001",
        uploadUrlLifetime = Duration.ofMinutes(5),
        sessionLifetime = Duration.ofHours(24),
        callTimeout = Duration.ofSeconds(10),
        attemptTimeout = Duration.ofSeconds(5),
    )

    private fun manifest(now: Instant) = IngestionManifest(
        ingestionId = UUID.fromString("13000000-0000-0000-0000-000000000001"),
        requestDigest = "b".repeat(64),
        editionId = UUID.fromString("11000000-0000-0000-0000-000000000001"),
        format = DigitalFormat.PDF,
        mediaType = "application/pdf",
        sizeBytes = 4096,
        sha256 = "a".repeat(64),
        quarantineObjectKey = "quarantine/digital-content/aa/13000000-0000-0000-0000-000000000001/${"a".repeat(64)}.pdf",
        sourceProvider = "OpenStax",
        sourceUri = "https://openstax.org/example",
        licenseExpression = "CC-BY-4.0",
        attribution = "Example Engineering Text, OpenStax",
        rightsVerifiedAt = now,
        rightsExpiresAt = null,
        actorFingerprint = "c".repeat(64),
        expiresAt = now.plusSeconds(86_400),
    )
}
