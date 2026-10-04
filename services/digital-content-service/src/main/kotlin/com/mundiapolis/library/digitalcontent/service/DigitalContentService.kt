package com.mundiapolis.library.digitalcontent.service

import com.mundiapolis.library.digitalcontent.dto.EditionDownloadAvailability
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface DigitalAssetReader {
    fun findGloballyDownloadable(editionId: UUID, now: Instant): EditionDownloadAvailability
}

class DigitalContentService(
    private val reader: DigitalAssetReader,
    private val clock: Clock,
) {
    fun availability(editionId: UUID): EditionDownloadAvailability =
        reader.findGloballyDownloadable(editionId, clock.instant())
}
