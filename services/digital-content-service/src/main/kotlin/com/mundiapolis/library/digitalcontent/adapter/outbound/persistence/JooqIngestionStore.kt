package com.mundiapolis.library.digitalcontent.adapter.outbound.persistence

import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_INGESTION
import com.mundiapolis.library.digitalcontent.dto.DigitalFormat
import com.mundiapolis.library.digitalcontent.service.IngestionConflictException
import com.mundiapolis.library.digitalcontent.service.IngestionManifest
import com.mundiapolis.library.digitalcontent.service.IngestionStore
import com.mundiapolis.library.digitalcontent.service.StoredIngestion
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqIngestionStore(
    private val dsl: DSLContext,
) : IngestionStore {
    @Transactional
    override fun createOrLoad(manifest: IngestionManifest): StoredIngestion {
        val created = dsl.insertInto(DIGITAL_CONTENT_INGESTION)
            .set(DIGITAL_CONTENT_INGESTION.INGESTION_ID, manifest.ingestionId)
            .set(DIGITAL_CONTENT_INGESTION.REQUEST_DIGEST, manifest.requestDigest)
            .set(DIGITAL_CONTENT_INGESTION.EDITION_ID, manifest.editionId)
            .set(DIGITAL_CONTENT_INGESTION.FORMAT, manifest.format.name)
            .set(DIGITAL_CONTENT_INGESTION.MEDIA_TYPE, manifest.mediaType)
            .set(DIGITAL_CONTENT_INGESTION.SIZE_BYTES, manifest.sizeBytes)
            .set(DIGITAL_CONTENT_INGESTION.SHA256, manifest.sha256)
            .set(DIGITAL_CONTENT_INGESTION.QUARANTINE_OBJECT_KEY, manifest.quarantineObjectKey)
            .set(DIGITAL_CONTENT_INGESTION.SOURCE_PROVIDER, manifest.sourceProvider)
            .set(DIGITAL_CONTENT_INGESTION.SOURCE_URI, manifest.sourceUri)
            .set(DIGITAL_CONTENT_INGESTION.LICENSE_EXPRESSION, manifest.licenseExpression)
            .set(DIGITAL_CONTENT_INGESTION.ATTRIBUTION, manifest.attribution)
            .set(DIGITAL_CONTENT_INGESTION.RIGHTS_VERIFIED_AT, manifest.rightsVerifiedAt.utc())
            .set(DIGITAL_CONTENT_INGESTION.RIGHTS_EXPIRES_AT, manifest.rightsExpiresAt?.utc())
            .set(DIGITAL_CONTENT_INGESTION.STATE, "AWAITING_SCAN")
            .set(DIGITAL_CONTENT_INGESTION.ACTOR_FINGERPRINT, manifest.actorFingerprint)
            .set(DIGITAL_CONTENT_INGESTION.EXPIRES_AT, manifest.expiresAt.utc())
            .set(DIGITAL_CONTENT_INGESTION.CREATED_AT, manifest.rightsVerifiedAt.utc())
            .set(DIGITAL_CONTENT_INGESTION.UPDATED_AT, manifest.rightsVerifiedAt.utc())
            .onConflict(DIGITAL_CONTENT_INGESTION.INGESTION_ID)
            .doNothing()
            .execute() == 1

        val record = dsl.selectFrom(DIGITAL_CONTENT_INGESTION)
            .where(DIGITAL_CONTENT_INGESTION.INGESTION_ID.eq(manifest.ingestionId))
            .forUpdate()
            .fetchSingle()
        if (record.requestDigest != manifest.requestDigest || record.actorFingerprint != manifest.actorFingerprint) {
            throw IngestionConflictException()
        }
        return StoredIngestion(record.toManifest(), replayed = !created)
    }

    private fun Record.toManifest() = IngestionManifest(
        ingestionId = requireNotNull(get(DIGITAL_CONTENT_INGESTION.INGESTION_ID)),
        requestDigest = requireNotNull(get(DIGITAL_CONTENT_INGESTION.REQUEST_DIGEST)),
        editionId = requireNotNull(get(DIGITAL_CONTENT_INGESTION.EDITION_ID)),
        format = DigitalFormat.valueOf(requireNotNull(get(DIGITAL_CONTENT_INGESTION.FORMAT))),
        mediaType = requireNotNull(get(DIGITAL_CONTENT_INGESTION.MEDIA_TYPE)),
        sizeBytes = requireNotNull(get(DIGITAL_CONTENT_INGESTION.SIZE_BYTES)),
        sha256 = requireNotNull(get(DIGITAL_CONTENT_INGESTION.SHA256)),
        quarantineObjectKey = requireNotNull(get(DIGITAL_CONTENT_INGESTION.QUARANTINE_OBJECT_KEY)),
        sourceProvider = requireNotNull(get(DIGITAL_CONTENT_INGESTION.SOURCE_PROVIDER)),
        sourceUri = requireNotNull(get(DIGITAL_CONTENT_INGESTION.SOURCE_URI)),
        licenseExpression = requireNotNull(get(DIGITAL_CONTENT_INGESTION.LICENSE_EXPRESSION)),
        attribution = requireNotNull(get(DIGITAL_CONTENT_INGESTION.ATTRIBUTION)),
        rightsVerifiedAt = requireNotNull(get(DIGITAL_CONTENT_INGESTION.RIGHTS_VERIFIED_AT)).toInstant(),
        rightsExpiresAt = get(DIGITAL_CONTENT_INGESTION.RIGHTS_EXPIRES_AT)?.toInstant(),
        actorFingerprint = requireNotNull(get(DIGITAL_CONTENT_INGESTION.ACTOR_FINGERPRINT)),
        expiresAt = requireNotNull(get(DIGITAL_CONTENT_INGESTION.EXPIRES_AT)).toInstant(),
    )

    private fun java.time.Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
