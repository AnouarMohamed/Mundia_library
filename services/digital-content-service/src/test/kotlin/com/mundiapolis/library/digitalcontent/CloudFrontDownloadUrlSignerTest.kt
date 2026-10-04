package com.mundiapolis.library.digitalcontent

import com.mundiapolis.library.digitalcontent.config.CloudFrontProperties
import com.mundiapolis.library.digitalcontent.service.CloudFrontDownloadUrlSigner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64

class CloudFrontDownloadUrlSignerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `signs an exact short lived policy with rsa sha256`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val keyPath = temporaryDirectory.resolve("cloudfront-private.pem")
        Files.writeString(keyPath, keyPair.private.encoded.toPem(), StandardCharsets.US_ASCII)
        val signer = CloudFrontDownloadUrlSigner(properties(keyPath))
        val issuedAt = Instant.parse("2026-10-04T12:00:00Z")

        val signed = signer.sign(OBJECT_KEY, issuedAt)

        assertEquals(issuedAt.plusSeconds(60), signed.expiresAt)
        val resource = "https://downloads.example.test/$OBJECT_KEY"
        assertTrue(signed.url.startsWith("$resource?"))
        val parameters = signed.url.substringAfter('?')
            .split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("K12345678", parameters["Key-Pair-Id"])
        assertEquals("SHA256", parameters["Hash-Algorithm"])
        val policy = String(parameters.getValue("Policy").cloudFrontDecode(), StandardCharsets.UTF_8)
        assertEquals(
            """{"Statement":[{"Resource":"$resource","Condition":{"DateLessThan":{"AWS:EpochTime":1791115260}}}]}""",
            policy,
        )
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update(policy.toByteArray(StandardCharsets.UTF_8))
        }
        assertTrue(verifier.verify(parameters.getValue("Signature").cloudFrontDecode()))
    }

    @Test
    fun `rejects weak signing keys`() {
        val weakKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val keyPath = temporaryDirectory.resolve("weak.pem")
        Files.writeString(keyPath, weakKey.private.encoded.toPem(), StandardCharsets.US_ASCII)

        assertThrows(IllegalArgumentException::class.java) {
            CloudFrontDownloadUrlSigner(properties(keyPath))
        }
    }

    private fun properties(keyPath: Path) = CloudFrontProperties(
        enabled = true,
        baseUrl = "https://downloads.example.test",
        keyPairId = "K12345678",
        privateKeyPath = keyPath.toString(),
        urlLifetime = Duration.ofSeconds(60),
    )

    private fun ByteArray.toPem(): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(this) +
            "\n-----END PRIVATE KEY-----\n"

    private fun String.cloudFrontDecode(): ByteArray = Base64.getDecoder().decode(
        replace('-', '+').replace('_', '=').replace('~', '/'),
    )

    private companion object {
        const val OBJECT_KEY =
            "digital-content/ab/12000000-0000-0000-0000-000000000001/" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.pdf"
    }
}
