package com.mundiapolis.library.digitalcontent.service

import com.mundiapolis.library.digitalcontent.dto.EditionDownloadAvailability
import com.mundiapolis.library.digitalcontent.dto.DownloadAuthorization
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface DigitalAssetReader {
    fun findGloballyDownloadable(editionId: UUID, now: Instant): EditionDownloadAvailability

    fun lockAuthorizable(assetId: UUID, now: Instant): AuthorizableAsset?

    fun recordAuthorization(
        authorizationId: UUID,
        assetId: UUID,
        actorFingerprint: String,
        issuedAt: Instant,
        expiresAt: Instant,
    )
}

data class AuthorizableAsset(
    val assetId: UUID,
    val objectKey: String,
)

open class DigitalContentService(
    private val reader: DigitalAssetReader,
    private val signer: DownloadUrlSigner,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    open fun availability(editionId: UUID): EditionDownloadAvailability =
        reader.findGloballyDownloadable(editionId, clock.instant())

    @Transactional
    open fun authorize(assetId: UUID, actorFingerprint: String): DownloadAuthorization {
        require(ACTOR_FINGERPRINT.matches(actorFingerprint))
        val issuedAt = clock.instant()
        val asset = reader.lockAuthorizable(assetId, issuedAt)
            ?: throw DownloadNotAvailableException()
        val signed = signer.sign(asset.objectKey, issuedAt)
        val authorizationId = UUID.randomUUID()
        reader.recordAuthorization(
            authorizationId,
            asset.assetId,
            actorFingerprint,
            issuedAt,
            signed.expiresAt,
        )
        return DownloadAuthorization(
            authorizationId = authorizationId,
            assetId = asset.assetId,
            downloadUrl = signed.url,
            expiresAt = signed.expiresAt,
        )
    }

    private companion object {
        val ACTOR_FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}

class DownloadNotAvailableException : RuntimeException()
