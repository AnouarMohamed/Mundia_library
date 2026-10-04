package com.mundiapolis.library.digitalcontent.service

import com.mundiapolis.library.digitalcontent.dto.CreateIngestionRequest
import com.mundiapolis.library.digitalcontent.dto.DigitalFormat
import com.mundiapolis.library.digitalcontent.dto.IngestionUploadGrant
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

data class IngestionManifest(
    val ingestionId: UUID,
    val requestDigest: String,
    val editionId: UUID,
    val format: DigitalFormat,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val quarantineObjectKey: String,
    val sourceProvider: String,
    val sourceUri: String,
    val licenseExpression: String,
    val attribution: String,
    val rightsVerifiedAt: Instant,
    val rightsExpiresAt: Instant?,
    val actorFingerprint: String,
    val expiresAt: Instant,
)

data class StoredIngestion(val manifest: IngestionManifest, val replayed: Boolean)

data class SignedUpload(
    val url: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

fun interface IngestionStore {
    fun createOrLoad(manifest: IngestionManifest): StoredIngestion
}

fun interface QuarantineUploadAuthorizer {
    fun authorize(manifest: IngestionManifest, issuedAt: Instant): SignedUpload
}

class DisabledQuarantineUploadAuthorizer : QuarantineUploadAuthorizer {
    override fun authorize(manifest: IngestionManifest, issuedAt: Instant): SignedUpload =
        throw IngestionUnavailableException()
}

class IngestionService(
    private val store: IngestionStore,
    private val authorizer: QuarantineUploadAuthorizer,
    private val clock: Clock,
    private val sessionLifetimeSeconds: Long,
) {
    fun create(
        ingestionId: UUID,
        request: CreateIngestionRequest,
        actorFingerprint: String,
    ): IngestionUploadGrant {
        require(ACTOR_FINGERPRINT.matches(actorFingerprint))
        val now = clock.instant()
        validate(request, now)
        val extension = request.format.name.lowercase()
        val mediaType = when (request.format) {
            DigitalFormat.PDF -> "application/pdf"
            DigitalFormat.EPUB -> "application/epub+zip"
        }
        val canonical = canonicalRequest(request, mediaType)
        val requestDigest = sha256(canonical)
        val manifest = IngestionManifest(
            ingestionId = ingestionId,
            requestDigest = requestDigest,
            editionId = request.editionId,
            format = request.format,
            mediaType = mediaType,
            sizeBytes = request.sizeBytes,
            sha256 = request.sha256,
            quarantineObjectKey = "quarantine/digital-content/${request.sha256.take(2)}/$ingestionId/${request.sha256}.$extension",
            sourceProvider = request.sourceProvider.trim(),
            sourceUri = request.sourceUri,
            licenseExpression = request.licenseExpression,
            attribution = request.attribution.trim(),
            rightsVerifiedAt = now,
            rightsExpiresAt = request.rightsExpiresAt,
            actorFingerprint = actorFingerprint,
            expiresAt = now.plusSeconds(sessionLifetimeSeconds),
        )
        val stored = store.createOrLoad(manifest)
        if (!stored.manifest.expiresAt.isAfter(now)) throw IngestionExpiredException()
        val upload = authorizer.authorize(stored.manifest, now)
        return IngestionUploadGrant(
            ingestionId = ingestionId,
            state = "AWAITING_SCAN",
            uploadUrl = upload.url,
            method = "PUT",
            requiredHeaders = upload.headers,
            uploadExpiresAt = upload.expiresAt,
            sessionExpiresAt = stored.manifest.expiresAt,
            replayed = stored.replayed,
        )
    }

    private fun validate(request: CreateIngestionRequest, now: Instant) {
        val source = runCatching { URI(request.sourceUri) }.getOrNull()
        if (source == null || source.scheme != "https" || source.host.isNullOrBlank() || source.userInfo != null ||
            source.fragment != null || source.port !in listOf(-1, 443)
        ) {
            throw InvalidIngestionRequestException()
        }
        if (request.sourceProvider != request.sourceProvider.trim() ||
            request.attribution != request.attribution.trim() ||
            request.sourceProvider.any(Char::isISOControl) || request.attribution.any(Char::isISOControl)
        ) {
            throw InvalidIngestionRequestException()
        }
        if (request.rightsExpiresAt?.isAfter(now) == false) throw InvalidIngestionRequestException()
    }

    private fun canonicalRequest(request: CreateIngestionRequest, mediaType: String): String = listOf(
        request.editionId.toString(),
        request.format.name,
        mediaType,
        request.sizeBytes.toString(),
        request.sha256,
        request.sourceProvider,
        request.sourceUri,
        request.licenseExpression,
        request.attribution,
        request.rightsExpiresAt?.toString().orEmpty(),
    ).joinToString("\u0000")

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        val ACTOR_FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}

class IngestionConflictException : RuntimeException()
class IngestionExpiredException : RuntimeException()
class IngestionUnavailableException : RuntimeException()
class InvalidIngestionRequestException : RuntimeException()
