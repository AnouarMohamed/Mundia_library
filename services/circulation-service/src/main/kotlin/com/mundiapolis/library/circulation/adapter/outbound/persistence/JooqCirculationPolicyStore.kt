package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_POLICY_CURRENT
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_POLICY_REVISION
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationPolicyRevisionRecord
import com.mundiapolis.library.circulation.application.port.outbound.CirculationPolicyStore
import com.mundiapolis.library.circulation.domain.model.CirculationPolicy
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqCirculationPolicyStore(
    private val dsl: DSLContext,
) : CirculationPolicyStore {
    override fun current(): CirculationPolicy = selectCurrent(lock = false)

    override fun lockCurrent(): CirculationPolicy = selectCurrent(lock = true)

    override fun findRevision(revisionId: UUID): CirculationPolicy? =
        dsl.selectFrom(CIRCULATION_POLICY_REVISION)
            .where(CIRCULATION_POLICY_REVISION.REVISION_ID.eq(revisionId))
            .fetchOne()
            ?.toDomain()

    override fun install(policy: CirculationPolicy, expectedRevisionId: UUID): Boolean {
        val inserted = dsl.insertInto(CIRCULATION_POLICY_REVISION)
            .set(CIRCULATION_POLICY_REVISION.REVISION_ID, policy.revisionId)
            .set(CIRCULATION_POLICY_REVISION.SEQUENCE, policy.sequence)
            .set(
                CIRCULATION_POLICY_REVISION.DEFAULT_LOAN_PERIOD_SECONDS,
                policy.defaultLoanPeriod.seconds,
            )
            .set(CIRCULATION_POLICY_REVISION.RENEWAL_PERIOD_SECONDS, policy.renewalPeriod.seconds)
            .set(CIRCULATION_POLICY_REVISION.MAXIMUM_RENEWALS, policy.maximumRenewals)
            .set(CIRCULATION_POLICY_REVISION.FINE_CURRENCY, policy.fineCurrency)
            .set(
                CIRCULATION_POLICY_REVISION.RESERVATION_HOLD_PERIOD_SECONDS,
                policy.reservationHoldPeriod.seconds,
            )
            .set(
                CIRCULATION_POLICY_REVISION.MAXIMUM_ACTIVE_RESERVATIONS,
                policy.maximumActiveReservations,
            )
            .set(CIRCULATION_POLICY_REVISION.ACTOR_FINGERPRINT, policy.actorFingerprint)
            .set(CIRCULATION_POLICY_REVISION.EFFECTIVE_AT, policy.effectiveAt.toOffsetDateTime())
            .set(CIRCULATION_POLICY_REVISION.CREATED_AT, policy.effectiveAt.toOffsetDateTime())
            .execute()
        check(inserted == 1) { "Policy revision was not persisted" }

        return dsl.update(CIRCULATION_POLICY_CURRENT)
            .set(CIRCULATION_POLICY_CURRENT.REVISION_ID, policy.revisionId)
            .where(
                CIRCULATION_POLICY_CURRENT.SINGLETON.isTrue
                    .and(CIRCULATION_POLICY_CURRENT.REVISION_ID.eq(expectedRevisionId)),
            )
            .execute() == 1
    }

    private fun selectCurrent(lock: Boolean): CirculationPolicy {
        val select = dsl.select(CIRCULATION_POLICY_REVISION.fields().toList())
            .from(CIRCULATION_POLICY_CURRENT)
            .join(CIRCULATION_POLICY_REVISION)
            .on(
                CIRCULATION_POLICY_REVISION.REVISION_ID.eq(
                    CIRCULATION_POLICY_CURRENT.REVISION_ID,
                ),
            )
            .where(CIRCULATION_POLICY_CURRENT.SINGLETON.isTrue)
        val record = if (lock) select.forUpdate().fetchOne() else select.fetchOne()
        return requireNotNull(record).into(CIRCULATION_POLICY_REVISION).toDomain()
    }

    private fun CirculationPolicyRevisionRecord.toDomain(): CirculationPolicy = CirculationPolicy(
        revisionId = requireNotNull(revisionId),
        sequence = requireNotNull(sequence),
        defaultLoanPeriod = Duration.ofSeconds(requireNotNull(defaultLoanPeriodSeconds)),
        renewalPeriod = Duration.ofSeconds(requireNotNull(renewalPeriodSeconds)),
        maximumRenewals = requireNotNull(maximumRenewals),
        fineCurrency = requireNotNull(fineCurrency),
        reservationHoldPeriod = Duration.ofSeconds(requireNotNull(reservationHoldPeriodSeconds)),
        maximumActiveReservations = requireNotNull(maximumActiveReservations),
        effectiveAt = requireNotNull(effectiveAt).toInstant(),
        actorFingerprint = requireNotNull(actorFingerprint),
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
