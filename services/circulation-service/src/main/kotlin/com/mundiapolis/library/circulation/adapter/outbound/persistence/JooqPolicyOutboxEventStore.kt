package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.OUTBOX_EVENT
import com.mundiapolis.library.circulation.application.model.PolicyOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.PolicyOutboxEventStore
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqPolicyOutboxEventStore(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : PolicyOutboxEventStore {
    override fun append(event: PolicyOutboxEvent) {
        val policy = event.policy
        val payload = linkedMapOf<String, Any>(
            "revision" to policy.revisionId.toString(),
            "sequence" to policy.sequence,
            "defaultLoanPeriodSeconds" to policy.defaultLoanPeriod.seconds,
            "renewalPeriodSeconds" to policy.renewalPeriod.seconds,
            "maximumRenewals" to policy.maximumRenewals,
            "fineCurrency" to policy.fineCurrency,
            "reservationHoldPeriodSeconds" to policy.reservationHoldPeriod.seconds,
            "maximumActiveReservations" to policy.maximumActiveReservations,
            "effectiveAt" to policy.effectiveAt.toString(),
            "actorFingerprint" to policy.actorFingerprint,
        )
        val eventType = "circulation.policy.updated"
        val inserted = dsl.insertInto(OUTBOX_EVENT)
            .set(OUTBOX_EVENT.ID, event.id)
            .set(OUTBOX_EVENT.AGGREGATE_TYPE, "policy")
            .set(OUTBOX_EVENT.AGGREGATE_ID, POLICY_AGGREGATE_ID)
            .set(OUTBOX_EVENT.AGGREGATE_VERSION, policy.sequence)
            .set(OUTBOX_EVENT.EVENT_TYPE, eventType)
            .set(OUTBOX_EVENT.EVENT_VERSION, 1)
            .set(OUTBOX_EVENT.OCCURRED_AT, event.occurredAt.toOffsetDateTime())
            .set(OUTBOX_EVENT.PAYLOAD, JSON.valueOf(objectMapper.writeValueAsString(payload)))
            .set(
                OUTBOX_EVENT.HEADERS,
                JSON.valueOf(
                    objectMapper.writeValueAsString(
                        mapOf("contentType" to "application/json", "schema" to "$eventType.v1"),
                    ),
                ),
            )
            .set(OUTBOX_EVENT.CREATED_AT, event.occurredAt.toOffsetDateTime())
            .execute()
        check(inserted == 1) { "Policy outbox event was not persisted" }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        val POLICY_AGGREGATE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
