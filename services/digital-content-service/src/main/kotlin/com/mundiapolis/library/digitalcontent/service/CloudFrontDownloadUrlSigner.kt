package com.mundiapolis.library.digitalcontent.service

import com.mundiapolis.library.digitalcontent.config.CloudFrontProperties
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

class CloudFrontDownloadUrlSigner(
    private val properties: CloudFrontProperties,
) : DownloadUrlSigner {
    private val privateKey: RSAPrivateKey = loadPrivateKey()
    private val baseUrl = properties.baseUri().toASCIIString().removeSuffix("/")

    override fun sign(objectKey: String, issuedAt: Instant): SignedDownload {
        require(OBJECT_KEY.matches(objectKey)) { "Invalid private object key" }
        val expiresAt = issuedAt.plus(properties.urlLifetime)
        val resource = "$baseUrl/$objectKey"
        val policy =
            """{"Statement":[{"Resource":"$resource","Condition":{"DateLessThan":{"AWS:EpochTime":${expiresAt.epochSecond}}}}]}"""
        val signature = runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey)
                update(policy.toByteArray(StandardCharsets.UTF_8))
                sign().cloudFrontBase64()
            }
        }.getOrElse { failure -> throw DownloadSigningUnavailableException(failure) }
        val encodedPolicy = policy.toByteArray(StandardCharsets.UTF_8).cloudFrontBase64()
        return SignedDownload(
            url = "$resource?Policy=$encodedPolicy&Signature=$signature" +
                "&Key-Pair-Id=${properties.keyPairId}&Hash-Algorithm=SHA256",
            expiresAt = expiresAt,
        )
    }

    private fun loadPrivateKey(): RSAPrivateKey {
        val path = properties.keyPath()
        if (!Files.isRegularFile(path) || Files.size(path) !in 1..MAXIMUM_KEY_BYTES) {
            throw IllegalArgumentException("CloudFront private key file is unavailable or invalid")
        }
        val pem = Files.readString(path, StandardCharsets.US_ASCII)
        val match = PKCS8_PEM.matchEntire(pem.trim())
            ?: throw IllegalArgumentException("CloudFront private key must be an unencrypted PKCS#8 PEM")
        val encoded = runCatching { Base64.getMimeDecoder().decode(match.groupValues[1]) }
            .getOrElse { throw IllegalArgumentException("CloudFront private key PEM is invalid", it) }
        val key = runCatching {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
        }.getOrElse { throw IllegalArgumentException("CloudFront private key is invalid", it) }
        require(key is RSAPrivateKey && key.modulus.bitLength() >= MINIMUM_RSA_BITS) {
            "CloudFront private key must be RSA with at least 2048 bits"
        }
        return key
    }

    private fun ByteArray.cloudFrontBase64(): String = Base64.getEncoder().encodeToString(this)
        .replace('+', '-')
        .replace('=', '_')
        .replace('/', '~')

    private companion object {
        const val MAXIMUM_KEY_BYTES = 16L * 1024
        const val MINIMUM_RSA_BITS = 2048
        val OBJECT_KEY = Regex(
            "^digital-content/[0-9a-f]{2}/[0-9a-f-]{36}/[0-9a-f]{64}[.](pdf|epub)$",
        )
        val PKCS8_PEM = Regex(
            "-----BEGIN PRIVATE KEY-----\\s+([A-Za-z0-9+/=\\r\\n]+)\\s+-----END PRIVATE KEY-----",
        )
    }
}

class DisabledDownloadUrlSigner : DownloadUrlSigner {
    override fun sign(objectKey: String, issuedAt: Instant): SignedDownload =
        throw DownloadSigningUnavailableException()
}
