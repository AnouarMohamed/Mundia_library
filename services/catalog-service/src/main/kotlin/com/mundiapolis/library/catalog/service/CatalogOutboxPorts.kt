package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.dto.BrokerAcknowledgement
import com.mundiapolis.library.catalog.dto.CatalogOutboxFailureCode
import com.mundiapolis.library.catalog.dto.CatalogOutboxFailureDisposition
import com.mundiapolis.library.catalog.dto.CatalogOutboxStatistics
import com.mundiapolis.library.catalog.dto.ClaimedCatalogOutboxEvent
import com.mundiapolis.library.catalog.dto.EncodedCatalogEvent
import java.time.Instant

interface CatalogOutboxStore {
    fun claimBatch(
        owner: String,
        now: Instant,
        leaseExpiresAt: Instant,
        batchSize: Int,
    ): List<ClaimedCatalogOutboxEvent>

    fun markPublished(
        owner: String,
        event: ClaimedCatalogOutboxEvent,
        acknowledgement: BrokerAcknowledgement,
        publishedAt: Instant,
    ): Boolean

    fun recordFailure(
        owner: String,
        event: ClaimedCatalogOutboxEvent,
        code: CatalogOutboxFailureCode,
        failedAt: Instant,
        nextAttemptAt: Instant,
        maximumAttempts: Int,
        blockImmediately: Boolean,
    ): CatalogOutboxFailureDisposition

    fun deletePublishedBefore(cutoff: Instant, batchSize: Int): Int

    fun statistics(now: Instant): CatalogOutboxStatistics
}

fun interface CatalogEventEncoder {
    fun encode(event: ClaimedCatalogOutboxEvent): EncodedCatalogEvent
}

fun interface CatalogBrokerPublisher {
    fun publish(event: EncodedCatalogEvent): BrokerAcknowledgement
}
