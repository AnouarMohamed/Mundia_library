package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.Tables.CIRCULATION_MEMBER_ELIGIBILITY
import com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated.tables.records.CirculationMemberEligibilityRecord
import com.mundiapolis.library.circulation.application.port.outbound.MemberEligibilityStore
import com.mundiapolis.library.circulation.domain.model.EligibilityReasonCode
import com.mundiapolis.library.circulation.domain.model.MemberEligibility
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqMemberEligibilityStore(
    private val dsl: DSLContext,
) : MemberEligibilityStore {
    override fun lockMember(memberId: MemberId) {
        dsl.fetch(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            memberId.value.toString(),
        )
    }

    override fun find(memberId: MemberId): MemberEligibility? = dsl
        .selectFrom(CIRCULATION_MEMBER_ELIGIBILITY)
        .where(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID.eq(memberId.value))
        .fetchOne()
        ?.toDomain()

    override fun save(
        eligibility: MemberEligibility,
        expectedSourceVersion: Long?,
        now: Instant,
    ): Boolean {
        if (expectedSourceVersion == null) {
            return dsl.insertInto(CIRCULATION_MEMBER_ELIGIBILITY)
                .set(CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID, eligibility.memberId.value)
                .set(CIRCULATION_MEMBER_ELIGIBILITY.STATUS, eligibility.status.name)
                .set(
                    CIRCULATION_MEMBER_ELIGIBILITY.REASON_CODE,
                    eligibility.reasonCode?.value,
                )
                .set(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION, eligibility.sourceVersion)
                .set(
                    CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_OCCURRED_AT,
                    eligibility.sourceOccurredAt.toOffsetDateTime(),
                )
                .set(CIRCULATION_MEMBER_ELIGIBILITY.CREATED_AT, now.toOffsetDateTime())
                .set(CIRCULATION_MEMBER_ELIGIBILITY.UPDATED_AT, now.toOffsetDateTime())
                .onConflictDoNothing()
                .execute() == 1
        }

        return dsl.update(CIRCULATION_MEMBER_ELIGIBILITY)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.STATUS, eligibility.status.name)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.REASON_CODE, eligibility.reasonCode?.value)
            .set(CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION, eligibility.sourceVersion)
            .set(
                CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_OCCURRED_AT,
                eligibility.sourceOccurredAt.toOffsetDateTime(),
            )
            .set(CIRCULATION_MEMBER_ELIGIBILITY.UPDATED_AT, now.toOffsetDateTime())
            .where(
                CIRCULATION_MEMBER_ELIGIBILITY.MEMBER_ID.eq(eligibility.memberId.value)
                    .and(
                        CIRCULATION_MEMBER_ELIGIBILITY.SOURCE_VERSION.eq(expectedSourceVersion),
                    ),
            )
            .execute() == 1
    }

    private fun CirculationMemberEligibilityRecord.toDomain(): MemberEligibility =
        MemberEligibility(
            memberId = MemberId(requireNotNull(memberId)),
            status = MemberEligibilityStatus.valueOf(requireNotNull(status)),
            reasonCode = reasonCode?.let(EligibilityReasonCode::parse),
            sourceVersion = requireNotNull(sourceVersion),
            sourceOccurredAt = requireNotNull(sourceOccurredAt).toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
