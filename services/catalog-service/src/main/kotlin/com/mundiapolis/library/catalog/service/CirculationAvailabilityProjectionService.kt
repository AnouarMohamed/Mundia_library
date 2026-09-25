package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.adapter.outbound.persistence.JooqAvailabilityProjectionRepository
import com.mundiapolis.library.catalog.dto.AvailabilityEventExecution
import com.mundiapolis.library.catalog.dto.CirculationCopyEvent
import com.mundiapolis.library.catalog.dto.CirculationEventClockSkewException
import com.mundiapolis.library.catalog.dto.CirculationEventConflictException
import com.mundiapolis.library.catalog.dto.CirculationEventGapException
import com.mundiapolis.library.catalog.dto.ConsumerEventDisposition
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

@Service
class CirculationAvailabilityProjectionService(
    private val repository: JooqAvailabilityProjectionRepository,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) : CirculationAvailabilityEventHandler {
    override fun apply(event: CirculationCopyEvent): AvailabilityEventExecution =
        requireNotNull(
            transactionTemplate.execute {
                val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                val occurredAt = event.occurredAt.truncatedTo(ChronoUnit.MICROS)
                if (occurredAt > now.plus(MAXIMUM_FUTURE_CLOCK_SKEW)) {
                    throw CirculationEventClockSkewException()
                }
                val normalized = event.copy(occurredAt = occurredAt)
                repository.lockEdition(normalized.editionId)
                repository.lockCopy(normalized.copyId)

                val processed = repository.findInbox(CONSUMER_NAME, normalized.eventId)
                if (processed != null) {
                    if (processed.payloadSha256 != normalized.payloadSha256) {
                        throw CirculationEventConflictException()
                    }
                    val projected = repository.findCopy(normalized.copyId)
                        ?: throw CirculationEventConflictException()
                    if (
                        projected.editionId != normalized.editionId ||
                        projected.sourceVersion < normalized.aggregateVersion
                    ) {
                        throw CirculationEventConflictException()
                    }
                    return@execute AvailabilityEventExecution(processed.disposition, replayed = true)
                }

                val current = repository.findCopy(normalized.copyId)
                if (current != null && current.editionId != normalized.editionId) {
                    throw CirculationEventConflictException()
                }
                val expectedVersion = current?.sourceVersion?.plus(1) ?: 0L
                val disposition = when {
                    normalized.aggregateVersion < expectedVersion -> ConsumerEventDisposition.STALE
                    normalized.aggregateVersion > expectedVersion ->
                        throw CirculationEventGapException(expectedVersion, normalized.aggregateVersion)
                    else -> ConsumerEventDisposition.APPLIED
                }

                if (
                    disposition == ConsumerEventDisposition.APPLIED &&
                    !repository.saveCopy(normalized, current?.sourceVersion, now)
                ) {
                    throw CirculationEventConflictException()
                }
                if (disposition == ConsumerEventDisposition.APPLIED) {
                    repository.recomputeEdition(normalized.editionId, now)
                }
                if (!repository.appendInbox(CONSUMER_NAME, normalized, disposition, now)) {
                    throw CirculationEventConflictException()
                }
                AvailabilityEventExecution(disposition, replayed = false)
            },
        )

    companion object {
        const val CONSUMER_NAME = "catalog-circulation-availability-v1"
        private val MAXIMUM_FUTURE_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}

fun interface CirculationAvailabilityEventHandler {
    fun apply(event: CirculationCopyEvent): AvailabilityEventExecution
}
