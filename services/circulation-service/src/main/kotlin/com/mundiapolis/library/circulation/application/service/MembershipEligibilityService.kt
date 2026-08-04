package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.EligibilityEventDisposition
import com.mundiapolis.library.circulation.application.model.EligibilityEventExecution
import com.mundiapolis.library.circulation.application.model.MemberEligibilityNotFoundException
import com.mundiapolis.library.circulation.application.model.MemberEligibilityView
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityEvent
import com.mundiapolis.library.circulation.application.model.MembershipEventClockSkewException
import com.mundiapolis.library.circulation.application.model.MembershipEventConflictException
import com.mundiapolis.library.circulation.application.model.MembershipEventGapException
import com.mundiapolis.library.circulation.application.model.ProcessedConsumerEvent
import com.mundiapolis.library.circulation.application.port.inbound.ApplyMembershipEligibilityEventUseCase
import com.mundiapolis.library.circulation.application.port.inbound.GetMemberEligibilityQuery
import com.mundiapolis.library.circulation.application.port.outbound.ConsumerInboxStore
import com.mundiapolis.library.circulation.application.port.outbound.MemberEligibilityStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.MemberEligibility
import com.mundiapolis.library.circulation.domain.model.MemberId
import java.time.Duration
import java.time.temporal.ChronoUnit

class MembershipEligibilityService(
    private val transactionRunner: TransactionRunner,
    private val eligibilityStore: MemberEligibilityStore,
    private val inboxStore: ConsumerInboxStore,
    private val timeProvider: TimeProvider,
    private val maximumFutureClockSkew: Duration,
) : ApplyMembershipEligibilityEventUseCase,
    GetMemberEligibilityQuery {
    override fun apply(event: MembershipEligibilityEvent): EligibilityEventExecution =
        transactionRunner.required {
            val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
            val occurredAt = event.occurredAt.truncatedTo(ChronoUnit.MICROS)
            if (occurredAt > now.plus(maximumFutureClockSkew)) {
                throw MembershipEventClockSkewException(maximumFutureClockSkew)
            }
            val normalized = event.copy(occurredAt = occurredAt)
            val fingerprint = normalized.payloadFingerprint()

            eligibilityStore.lockMember(normalized.memberId)
            val processed = inboxStore.find(CONSUMER_NAME, normalized.eventId)
            if (processed != null) {
                if (processed.payloadFingerprint != fingerprint) {
                    throw MembershipEventConflictException()
                }
                val existing = eligibilityStore.find(normalized.memberId)
                    ?: throw MembershipEventConflictException()
                return@required EligibilityEventExecution(
                    disposition = processed.disposition,
                    replayed = true,
                    eligibility = existing,
                )
            }

            val current = eligibilityStore.find(normalized.memberId)
            val expectedVersion = current?.sourceVersion?.plus(1) ?: 0L
            val disposition = when {
                normalized.aggregateVersion < expectedVersion -> EligibilityEventDisposition.STALE
                normalized.aggregateVersion > expectedVersion -> {
                    throw MembershipEventGapException(expectedVersion, normalized.aggregateVersion)
                }
                else -> EligibilityEventDisposition.APPLIED
            }

            val result = if (disposition == EligibilityEventDisposition.APPLIED) {
                val eligibility = MemberEligibility(
                    memberId = normalized.memberId,
                    status = normalized.status,
                    reasonCode = normalized.reasonCode,
                    sourceVersion = normalized.aggregateVersion,
                    sourceOccurredAt = normalized.occurredAt,
                )
                if (!eligibilityStore.save(eligibility, current?.sourceVersion, now)) {
                    throw MembershipEventConflictException()
                }
                eligibility
            } else {
                requireNotNull(current)
            }

            val inboxEvent = ProcessedConsumerEvent(
                consumerName = CONSUMER_NAME,
                eventId = normalized.eventId,
                eventType = normalized.eventType,
                eventVersion = normalized.eventVersion,
                aggregateType = AGGREGATE_TYPE,
                aggregateId = normalized.memberId.value,
                aggregateVersion = normalized.aggregateVersion,
                payloadFingerprint = fingerprint,
                disposition = disposition,
                receivedAt = now,
                processedAt = now,
            )
            if (!inboxStore.append(inboxEvent)) {
                throw MembershipEventConflictException()
            }
            EligibilityEventExecution(
                disposition = disposition,
                replayed = false,
                eligibility = result,
            )
        }

    override fun get(memberId: MemberId, principal: CommandPrincipal): MemberEligibilityView {
        if (!principal.canActOnBehalf && principal.membershipId != memberId) {
            throw MemberEligibilityNotFoundException(memberId)
        }
        return eligibilityStore.find(memberId)
            ?.let(MemberEligibilityView::from)
            ?: throw MemberEligibilityNotFoundException(memberId)
    }

    private companion object {
        const val CONSUMER_NAME = "circulation-membership-eligibility-v1"
        const val AGGREGATE_TYPE = "member"
    }
}
