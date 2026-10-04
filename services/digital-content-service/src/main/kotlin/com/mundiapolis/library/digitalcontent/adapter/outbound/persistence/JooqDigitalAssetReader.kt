package com.mundiapolis.library.digitalcontent.adapter.outbound.persistence

import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_ASSET
import com.mundiapolis.library.digitalcontent.dto.DigitalFormat
import com.mundiapolis.library.digitalcontent.dto.DownloadableFormat
import com.mundiapolis.library.digitalcontent.dto.EditionDownloadAvailability
import com.mundiapolis.library.digitalcontent.service.DigitalAssetReader
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqDigitalAssetReader(
    private val dsl: DSLContext,
) : DigitalAssetReader {
    override fun findGloballyDownloadable(
        editionId: UUID,
        now: Instant,
    ): EditionDownloadAvailability {
        val timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        val formats = dsl.select(
            DIGITAL_CONTENT_ASSET.ASSET_ID,
            DIGITAL_CONTENT_ASSET.FORMAT,
            DIGITAL_CONTENT_ASSET.MEDIA_TYPE,
            DIGITAL_CONTENT_ASSET.SIZE_BYTES,
            DIGITAL_CONTENT_ASSET.SHA256,
            DIGITAL_CONTENT_ASSET.LICENSE_EXPRESSION,
            DIGITAL_CONTENT_ASSET.ATTRIBUTION,
        )
            .from(DIGITAL_CONTENT_ASSET)
            .where(DIGITAL_CONTENT_ASSET.EDITION_ID.eq(editionId))
            .and(DIGITAL_CONTENT_ASSET.RIGHTS_STATUS.eq("VERIFIED"))
            .and(DIGITAL_CONTENT_ASSET.RIGHTS_VERIFIED_AT.le(timestamp))
            .and(
                DIGITAL_CONTENT_ASSET.RIGHTS_EXPIRES_AT.isNull
                    .or(DIGITAL_CONTENT_ASSET.RIGHTS_EXPIRES_AT.gt(timestamp)),
            )
            .and(DIGITAL_CONTENT_ASSET.TERRITORY_SCOPE.eq("GLOBAL"))
            .and(DIGITAL_CONTENT_ASSET.MALWARE_SCAN_STATUS.eq("CLEAN"))
            .and(DIGITAL_CONTENT_ASSET.PUBLICATION_STATUS.eq("PUBLISHED"))
            .orderBy(DIGITAL_CONTENT_ASSET.FORMAT.asc(), DIGITAL_CONTENT_ASSET.ASSET_ID.asc())
            .fetch { record ->
                DownloadableFormat(
                    assetId = requireNotNull(record[DIGITAL_CONTENT_ASSET.ASSET_ID]),
                    format = DigitalFormat.valueOf(requireNotNull(record[DIGITAL_CONTENT_ASSET.FORMAT])),
                    mediaType = requireNotNull(record[DIGITAL_CONTENT_ASSET.MEDIA_TYPE]),
                    sizeBytes = requireNotNull(record[DIGITAL_CONTENT_ASSET.SIZE_BYTES]),
                    sha256 = requireNotNull(record[DIGITAL_CONTENT_ASSET.SHA256]),
                    licenseExpression = requireNotNull(record[DIGITAL_CONTENT_ASSET.LICENSE_EXPRESSION]),
                    attribution = requireNotNull(record[DIGITAL_CONTENT_ASSET.ATTRIBUTION]),
                )
            }
        return EditionDownloadAvailability(editionId, formats.isNotEmpty(), formats)
    }
}
