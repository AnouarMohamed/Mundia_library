package com.mundiapolis.library.digitalcontent.service

import com.mundiapolis.library.digitalcontent.dto.ExternalDownloadAuthorization
import com.mundiapolis.library.digitalcontent.dto.ExternalResourceAvailability
import com.mundiapolis.library.digitalcontent.dto.ExternalResourceRegistration
import com.mundiapolis.library.digitalcontent.dto.ExternalLicenseExpression
import com.mundiapolis.library.digitalcontent.dto.ExternalMediaType
import com.mundiapolis.library.digitalcontent.dto.RegisterExternalResourceRequest
import org.springframework.transaction.annotation.Transactional
import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

interface ExternalResourceStore {
    fun register(command: ExternalResourceRegistrationCommand): ExternalResourceRegistration

    fun findAvailable(resourceId: UUID, now: Instant): ExternalResourceAvailability

    fun lockAuthorizable(resourceId: UUID, now: Instant): AuthorizableExternalResource?

    fun recordAuthorization(
        authorizationId: UUID,
        resourceId: UUID,
        actorFingerprint: String,
        issuedAt: Instant,
    )
}

data class ExternalResourceRegistrationCommand(
    val resourceId: UUID,
    val sourceProvider: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val mediaType: String,
    val licenseExpression: String,
    val licenseUrl: String,
    val attribution: String,
    val manifestSha256: String,
    val actorFingerprint: String,
    val verifiedAt: Instant,
)

data class AuthorizableExternalResource(
    val resourceId: UUID,
    val sourceProvider: String,
    val licenseExpression: String,
    val downloadUrl: String,
)

open class ExternalResourceService(
    private val store: ExternalResourceStore,
    private val clock: Clock,
) {
    @Transactional
    open fun register(
        resourceId: UUID,
        request: RegisterExternalResourceRequest,
        actorFingerprint: String,
    ): ExternalResourceRegistration {
        if (!ACTOR_FINGERPRINT.matches(actorFingerprint)) throw InvalidExternalResourceException()
        val provider = request.sourceProvider.trim()
        val attribution = request.attribution.trim()
        val source = strictPublicHttpsUrl(request.sourceUrl)
        val download = strictPublicHttpsUrl(request.downloadUrl)
        val license = strictPublicHttpsUrl(request.licenseUrl)
        val mediaType = ExternalMediaType.entries.find { it.value == request.mediaType }
            ?: throw InvalidExternalResourceException()
        val licenseExpression = ExternalLicenseExpression.entries.find {
            it.value == request.licenseExpression
        } ?: throw InvalidExternalResourceException()
        if (
            provider.length !in 2..100 ||
            attribution.length !in 2..1000 ||
            license != licenseExpression.canonicalUrl
        ) {
            throw InvalidExternalResourceException()
        }
        val manifest = listOf(
            resourceId.toString(),
            provider,
            source,
            download,
            mediaType.value,
            licenseExpression.value,
            license,
            attribution,
        ).joinToString("\u0000")
        return store.register(
            ExternalResourceRegistrationCommand(
                resourceId,
                provider,
                source,
                download,
                mediaType.value,
                licenseExpression.value,
                license,
                attribution,
                sha256(manifest),
                actorFingerprint,
                clock.instant(),
            ),
        )
    }

    @Transactional(readOnly = true)
    open fun availability(resourceId: UUID): ExternalResourceAvailability =
        store.findAvailable(resourceId, clock.instant())

    @Transactional
    open fun authorize(resourceId: UUID, actorFingerprint: String): ExternalDownloadAuthorization {
        if (!ACTOR_FINGERPRINT.matches(actorFingerprint)) throw InvalidExternalResourceException()
        val issuedAt = clock.instant()
        val resource = store.lockAuthorizable(resourceId, issuedAt)
            ?: throw DownloadNotAvailableException()
        val authorizationId = UUID.randomUUID()
        store.recordAuthorization(authorizationId, resourceId, actorFingerprint, issuedAt)
        return ExternalDownloadAuthorization(
            authorizationId,
            resource.resourceId,
            resource.sourceProvider,
            resource.licenseExpression,
            resource.downloadUrl,
        )
    }

    private fun strictPublicHttpsUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrElse { throw InvalidExternalResourceException() }
        val asciiHost = runCatching { IDN.toASCII(uri.host.orEmpty()) }
            .getOrElse { throw InvalidExternalResourceException() }
            .lowercase()
        if (
            uri.scheme != "https" ||
            asciiHost.isBlank() ||
            uri.rawUserInfo != null ||
            uri.port != -1 ||
            uri.rawFragment != null ||
            uri.rawPath.isNullOrBlank() ||
            uri.normalize().rawPath != uri.rawPath ||
            value.length > 2048 ||
            asciiHost == "localhost" ||
            asciiHost.endsWith('.') ||
            asciiHost.endsWith(".localhost") ||
            asciiHost.endsWith(".local") ||
            asciiHost.endsWith(".internal") ||
            IPV4_LITERAL.matches(asciiHost) ||
            asciiHost.contains(':') ||
            ENCODED_PATH_SEPARATOR.containsMatchIn(uri.rawPath)
        ) {
            throw InvalidExternalResourceException()
        }
        return buildString {
            append("https://")
            append(asciiHost)
            append(uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        val ACTOR_FINGERPRINT = Regex("^[0-9a-f]{64}$")
        val IPV4_LITERAL = Regex("^[0-9.]+$")
        val ENCODED_PATH_SEPARATOR = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)
    }
}

class ExternalResourceConflictException : RuntimeException()
class InvalidExternalResourceException : RuntimeException()
