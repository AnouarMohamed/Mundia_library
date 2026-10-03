package com.mundiapolis.library.notification.adapter.`in`.feedback

import com.mundiapolis.library.notification.config.SesFeedbackProperties
import com.mundiapolis.library.notification.dto.SesFeedbackContractException
import com.mundiapolis.library.notification.dto.SesFeedbackTransientException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class HttpsSnsSigningKeyProvider(
    private val httpClient: HttpClient,
    private val properties: SesFeedbackProperties,
    private val clock: Clock,
) : SnsSigningKeyProvider {
    private val cache = ConcurrentHashMap<URI, CachedKey>()

    override fun get(uri: URI): PublicKey {
        val now = clock.instant()
        cache[uri]?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.key }
        cache.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        if (cache.size >= MAXIMUM_CACHE_ENTRIES) {
            throw SesFeedbackContractException("SNS signing certificate cache limit exceeded")
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.certificateReadTimeout)
            .header("Accept", "application/x-pem-file, application/pem-certificate-chain")
            .GET()
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: Exception) {
            throw SesFeedbackTransientException("SNS signing certificate is unavailable")
        }
        response.body().use { body ->
            if (response.statusCode() != 200) throw SesFeedbackTransientException("SNS signing certificate is unavailable")
            val bytes = body.readNBytes(properties.maximumCertificateBytes + 1)
            if (bytes.size !in 1..properties.maximumCertificateBytes) {
                throw SesFeedbackContractException("SNS signing certificate size is invalid")
            }
            val certificate = try {
                CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
            } catch (_: Exception) {
                throw SesFeedbackContractException("SNS signing certificate is invalid")
            }
            try {
                certificate.checkValidity(Date.from(now))
            } catch (_: Exception) {
                throw SesFeedbackContractException("SNS signing certificate is not valid now")
            }
            val key = certificate.publicKey
            if (key.algorithm != "RSA") throw SesFeedbackContractException("SNS signing certificate key is invalid")
            cache[uri] = CachedKey(key, now.plus(properties.certificateCacheDuration))
            return key
        }
    }

    private data class CachedKey(val key: PublicKey, val expiresAt: Instant)

    private companion object {
        const val MAXIMUM_CACHE_ENTRIES = 8
    }
}
