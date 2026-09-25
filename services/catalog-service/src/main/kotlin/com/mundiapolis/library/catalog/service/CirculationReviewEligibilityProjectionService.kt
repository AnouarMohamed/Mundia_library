package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.adapter.outbound.persistence.JooqReviewEligibilityProjectionRepository
import com.mundiapolis.library.catalog.dto.AvailabilityEventExecution
import com.mundiapolis.library.catalog.dto.CirculationEventClockSkewException
import com.mundiapolis.library.catalog.dto.CirculationEventConflictException
import com.mundiapolis.library.catalog.dto.CirculationEventGapException
import com.mundiapolis.library.catalog.dto.CirculationLoanEvent
import com.mundiapolis.library.catalog.dto.ConsumerEventDisposition
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

@Service
class CirculationReviewEligibilityProjectionService(
    private val repository: JooqReviewEligibilityProjectionRepository,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) : CirculationLoanEventHandler {
    override fun apply(event: CirculationLoanEvent): AvailabilityEventExecution = requireNotNull(
        transactionTemplate.execute {
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val normalized = event.copy(occurredAt = event.occurredAt.truncatedTo(ChronoUnit.MICROS))
            if (normalized.occurredAt > now.plus(MAXIMUM_FUTURE_CLOCK_SKEW)) {
                throw CirculationEventClockSkewException()
            }
            repository.lockLoan(normalized.loanId)
            val processed = repository.findInbox(CONSUMER_NAME, normalized.eventId)
            if (processed != null) {
                if (processed.payloadSha256 != normalized.payloadSha256) {
                    throw CirculationEventConflictException()
                }
                val projected = repository.findLoan(normalized.loanId)
                    ?: throw CirculationEventConflictException()
                if (
                    projected.memberId != normalized.memberId ||
                    projected.editionId != normalized.editionId ||
                    projected.sourceVersion < normalized.aggregateVersion
                ) throw CirculationEventConflictException()
                return@execute AvailabilityEventExecution(processed.disposition, replayed = true)
            }

            val current = repository.findLoan(normalized.loanId)
            if (
                current != null &&
                (current.memberId != normalized.memberId || current.editionId != normalized.editionId)
            ) throw CirculationEventConflictException()
            val expectedVersion = current?.sourceVersion?.plus(1) ?: 0L
            val disposition = when {
                normalized.aggregateVersion < expectedVersion -> ConsumerEventDisposition.STALE
                normalized.aggregateVersion > expectedVersion ->
                    throw CirculationEventGapException(expectedVersion, normalized.aggregateVersion)
                else -> ConsumerEventDisposition.APPLIED
            }
            if (
                disposition == ConsumerEventDisposition.APPLIED &&
                !repository.saveLoan(normalized, current?.sourceVersion, now)
            ) throw CirculationEventConflictException()
            if (!repository.appendInbox(CONSUMER_NAME, normalized, disposition, now)) {
                throw CirculationEventConflictException()
            }
            AvailabilityEventExecution(disposition, replayed = false)
        },
    )

    private companion object {
        const val CONSUMER_NAME = CirculationAvailabilityProjectionService.CONSUMER_NAME
        val MAXIMUM_FUTURE_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}

fun interface CirculationLoanEventHandler {
    fun apply(event: CirculationLoanEvent): AvailabilityEventExecution
}
