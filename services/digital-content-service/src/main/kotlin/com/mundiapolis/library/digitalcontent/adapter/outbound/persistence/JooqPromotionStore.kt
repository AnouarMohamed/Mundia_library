package com.mundiapolis.library.digitalcontent.adapter.outbound.persistence

import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_ASSET
import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_INGESTION
import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_PROMOTION_JOB
import com.mundiapolis.library.digitalcontent.service.PromotedObject
import com.mundiapolis.library.digitalcontent.service.PromotionClaim
import com.mundiapolis.library.digitalcontent.service.PromotionConflictException
import com.mundiapolis.library.digitalcontent.service.PromotionLeaseLostException
import com.mundiapolis.library.digitalcontent.service.PromotionStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqPromotionStore(
    private val dsl: DSLContext,
) : PromotionStore {
    @Transactional
    override fun claim(now: Instant, leaseDuration: Duration): PromotionClaim? {
        val timestamp = now.utc()
        val job = dsl.selectFrom(DIGITAL_CONTENT_PROMOTION_JOB)
            .where(
                DIGITAL_CONTENT_PROMOTION_JOB.STATE.`in`("PENDING", "RETRY")
                    .and(DIGITAL_CONTENT_PROMOTION_JOB.NEXT_ATTEMPT_AT.le(timestamp))
                    .or(
                        DIGITAL_CONTENT_PROMOTION_JOB.STATE.eq("LEASED")
                            .and(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_EXPIRES_AT.le(timestamp)),
                    ),
            )
            .orderBy(DIGITAL_CONTENT_PROMOTION_JOB.NEXT_ATTEMPT_AT, DIGITAL_CONTENT_PROMOTION_JOB.INGESTION_ID)
            .limit(1)
            .forUpdate()
            .skipLocked()
            .fetchOne() ?: return null
        val ingestion = dsl.selectFrom(DIGITAL_CONTENT_INGESTION)
            .where(DIGITAL_CONTENT_INGESTION.INGESTION_ID.eq(job.ingestionId))
            .forUpdate()
            .fetchSingle()
        if (ingestion.state != "CLEAN") {
            dsl.update(DIGITAL_CONTENT_PROMOTION_JOB)
                .set(DIGITAL_CONTENT_PROMOTION_JOB.STATE, "CANCELLED")
                .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_TOKEN)
                .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_EXPIRES_AT)
                .set(DIGITAL_CONTENT_PROMOTION_JOB.LAST_ERROR_CODE, "INGESTION_NOT_CLEAN")
                .set(DIGITAL_CONTENT_PROMOTION_JOB.UPDATED_AT, timestamp)
                .where(DIGITAL_CONTENT_PROMOTION_JOB.INGESTION_ID.eq(job.ingestionId))
                .execute()
            return null
        }
        val leaseToken = UUID.randomUUID()
        val attempt = requireNotNull(job.attemptCount) + 1
        dsl.update(DIGITAL_CONTENT_PROMOTION_JOB)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.STATE, "LEASED")
            .set(DIGITAL_CONTENT_PROMOTION_JOB.ATTEMPT_COUNT, attempt)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_TOKEN, leaseToken)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_EXPIRES_AT, now.plus(leaseDuration).utc())
            .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LAST_ERROR_CODE)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.UPDATED_AT, timestamp)
            .where(DIGITAL_CONTENT_PROMOTION_JOB.INGESTION_ID.eq(job.ingestionId))
            .execute()
        return PromotionClaim(
            ingestionId = requireNotNull(job.ingestionId),
            assetId = requireNotNull(job.assetId),
            leaseToken = leaseToken,
            attempt = attempt,
            sourceObjectKey = requireNotNull(ingestion.quarantineObjectKey),
            sourceObjectVersionId = requireNotNull(ingestion.objectVersionId),
            sourceObjectEtag = requireNotNull(ingestion.objectEtag),
            destinationObjectKey = requireNotNull(job.destinationObjectKey),
            mediaType = requireNotNull(ingestion.mediaType),
            sizeBytes = requireNotNull(ingestion.sizeBytes),
            sha256 = requireNotNull(ingestion.sha256),
        )
    }

    @Transactional
    override fun complete(claim: PromotionClaim, promoted: PromotedObject, completedAt: Instant) {
        validatePromotedObject(claim, promoted)
        val job = lockActiveLease(claim, completedAt)
        val ingestion = dsl.selectFrom(DIGITAL_CONTENT_INGESTION)
            .where(DIGITAL_CONTENT_INGESTION.INGESTION_ID.eq(claim.ingestionId))
            .forUpdate()
            .fetchSingle()
        if (ingestion.state != "CLEAN" || ingestion.objectVersionId != claim.sourceObjectVersionId ||
            ingestion.objectEtag != claim.sourceObjectEtag || ingestion.sha256 != claim.sha256 ||
            ingestion.sizeBytes != claim.sizeBytes || ingestion.mediaType != claim.mediaType ||
            requireNotNull(ingestion.rightsVerifiedAt).toInstant().isAfter(completedAt) ||
            ingestion.rightsExpiresAt?.toInstant()?.isAfter(completedAt) == false
        ) {
            throw PromotionConflictException()
        }
        val existing = dsl.selectFrom(DIGITAL_CONTENT_ASSET)
            .where(DIGITAL_CONTENT_ASSET.EDITION_ID.eq(ingestion.editionId))
            .and(DIGITAL_CONTENT_ASSET.FORMAT.eq(ingestion.format))
            .forUpdate()
            .fetchOne()
        if (existing == null) {
            dsl.insertInto(DIGITAL_CONTENT_ASSET)
                .set(DIGITAL_CONTENT_ASSET.ASSET_ID, claim.assetId)
                .set(DIGITAL_CONTENT_ASSET.EDITION_ID, ingestion.editionId)
                .set(DIGITAL_CONTENT_ASSET.FORMAT, ingestion.format)
                .set(DIGITAL_CONTENT_ASSET.MEDIA_TYPE, ingestion.mediaType)
                .set(DIGITAL_CONTENT_ASSET.SIZE_BYTES, ingestion.sizeBytes)
                .set(DIGITAL_CONTENT_ASSET.SHA256, ingestion.sha256)
                .set(DIGITAL_CONTENT_ASSET.OBJECT_KEY, job.destinationObjectKey)
                .set(DIGITAL_CONTENT_ASSET.SOURCE_PROVIDER, ingestion.sourceProvider)
                .set(DIGITAL_CONTENT_ASSET.SOURCE_URI, ingestion.sourceUri)
                .set(DIGITAL_CONTENT_ASSET.LICENSE_EXPRESSION, ingestion.licenseExpression)
                .set(DIGITAL_CONTENT_ASSET.ATTRIBUTION, ingestion.attribution)
                .set(DIGITAL_CONTENT_ASSET.RIGHTS_STATUS, "VERIFIED")
                .set(DIGITAL_CONTENT_ASSET.RIGHTS_VERIFIED_AT, ingestion.rightsVerifiedAt)
                .set(DIGITAL_CONTENT_ASSET.RIGHTS_EXPIRES_AT, ingestion.rightsExpiresAt)
                .set(DIGITAL_CONTENT_ASSET.TERRITORY_SCOPE, "GLOBAL")
                .set(DIGITAL_CONTENT_ASSET.MALWARE_SCAN_STATUS, "CLEAN")
                .set(DIGITAL_CONTENT_ASSET.PUBLICATION_STATUS, "PUBLISHED")
                .set(DIGITAL_CONTENT_ASSET.VERSION, 0L)
                .set(DIGITAL_CONTENT_ASSET.CREATED_AT, completedAt.utc())
                .set(DIGITAL_CONTENT_ASSET.UPDATED_AT, completedAt.utc())
                .execute()
        } else if (existing.assetId != claim.assetId || existing.objectKey != job.destinationObjectKey ||
            existing.sha256 != claim.sha256 || existing.sizeBytes != claim.sizeBytes ||
            existing.mediaType != claim.mediaType
        ) {
            throw PromotionConflictException()
        }
        val timestamp = completedAt.utc()
        dsl.update(DIGITAL_CONTENT_INGESTION)
            .set(DIGITAL_CONTENT_INGESTION.STATE, "PROMOTED")
            .set(DIGITAL_CONTENT_INGESTION.UPDATED_AT, timestamp)
            .where(DIGITAL_CONTENT_INGESTION.INGESTION_ID.eq(claim.ingestionId))
            .execute()
        dsl.update(DIGITAL_CONTENT_PROMOTION_JOB)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.STATE, "COMPLETED")
            .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_TOKEN)
            .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_EXPIRES_AT)
            .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LAST_ERROR_CODE)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.PROMOTED_OBJECT_VERSION_ID, promoted.objectVersionId)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.PROMOTED_OBJECT_ETAG, promoted.objectEtag)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.PROMOTED_CHECKSUM_SHA256, promoted.checksumSha256)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.COMPLETED_AT, timestamp)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.UPDATED_AT, timestamp)
            .where(DIGITAL_CONTENT_PROMOTION_JOB.INGESTION_ID.eq(claim.ingestionId))
            .execute()
    }

    @Transactional
    override fun retry(claim: PromotionClaim, errorCode: String, retryAt: Instant, failedAt: Instant) {
        lockActiveLease(claim, failedAt)
        release(claim, "RETRY", errorCode, retryAt, failedAt)
    }

    @Transactional
    override fun block(claim: PromotionClaim, errorCode: String, failedAt: Instant) {
        lockActiveLease(claim, failedAt)
        release(claim, "BLOCKED", errorCode, failedAt, failedAt)
    }

    private fun lockActiveLease(claim: PromotionClaim, now: Instant) =
        dsl.selectFrom(DIGITAL_CONTENT_PROMOTION_JOB)
            .where(DIGITAL_CONTENT_PROMOTION_JOB.INGESTION_ID.eq(claim.ingestionId))
            .and(DIGITAL_CONTENT_PROMOTION_JOB.ASSET_ID.eq(claim.assetId))
            .and(DIGITAL_CONTENT_PROMOTION_JOB.DESTINATION_OBJECT_KEY.eq(claim.destinationObjectKey))
            .and(DIGITAL_CONTENT_PROMOTION_JOB.STATE.eq("LEASED"))
            .and(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_TOKEN.eq(claim.leaseToken))
            .and(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_EXPIRES_AT.gt(now.utc()))
            .forUpdate()
            .fetchOne() ?: throw PromotionLeaseLostException()

    private fun release(
        claim: PromotionClaim,
        state: String,
        errorCode: String,
        nextAttemptAt: Instant,
        updatedAt: Instant,
    ) {
        require(ERROR_CODE.matches(errorCode))
        dsl.update(DIGITAL_CONTENT_PROMOTION_JOB)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.STATE, state)
            .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_TOKEN)
            .setNull(DIGITAL_CONTENT_PROMOTION_JOB.LEASE_EXPIRES_AT)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.LAST_ERROR_CODE, errorCode)
            .set(DIGITAL_CONTENT_PROMOTION_JOB.NEXT_ATTEMPT_AT, nextAttemptAt.utc())
            .set(DIGITAL_CONTENT_PROMOTION_JOB.UPDATED_AT, updatedAt.utc())
            .where(DIGITAL_CONTENT_PROMOTION_JOB.INGESTION_ID.eq(claim.ingestionId))
            .execute()
    }

    private fun validatePromotedObject(claim: PromotionClaim, promoted: PromotedObject) {
        if (promoted.checksumSha256 != claim.sha256 || promoted.sizeBytes != claim.sizeBytes ||
            promoted.mediaType != claim.mediaType || promoted.objectVersionId.length !in 1..1024 ||
            promoted.objectEtag.length !in 1..128 || promoted.objectVersionId.any(Char::isISOControl) ||
            promoted.objectEtag.any(Char::isISOControl)
        ) {
            throw PromotionConflictException()
        }
    }

    private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{1,63}$")
    }
}
