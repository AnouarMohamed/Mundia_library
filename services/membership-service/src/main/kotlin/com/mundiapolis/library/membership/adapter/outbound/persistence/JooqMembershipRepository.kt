package com.mundiapolis.library.membership.adapter.outbound.persistence

import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_IDENTITY_EVIDENCE
import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_MEMBER
import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import com.mundiapolis.library.membership.dto.MembershipRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Clock
import java.util.UUID

@Repository
class JooqMembershipRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    fun findProfile(memberId: UUID): MemberProfile? = dsl
        .selectFrom(MEMBERSHIP_MEMBER)
        .where(MEMBERSHIP_MEMBER.MEMBER_ID.eq(memberId))
        .fetchOne()
        ?.let { member ->
            MemberProfile(
                memberId = requireNotNull(member.memberId).toString(),
                email = requireNotNull(member.email),
                fullName = requireNotNull(member.fullName),
                universityId = requireNotNull(member.universityId),
                status = AccountStatus.valueOf(requireNotNull(member.accountStatus)),
                role = MembershipRole.valueOf(requireNotNull(member.membershipRole)),
                createdAt = requireNotNull(member.createdAt).toInstant(),
                updatedAt = requireNotNull(member.updatedAt).toInstant(),
            )
        }

    fun findEligibility(memberId: UUID): MemberEligibility? = dsl
        .selectFrom(MEMBERSHIP_MEMBER)
        .where(MEMBERSHIP_MEMBER.MEMBER_ID.eq(memberId))
        .fetchOne()
        ?.let { member ->
            val status = AccountStatus.valueOf(requireNotNull(member.accountStatus))
            val maximum = requireNotNull(member.maxActiveLoans)
            val current = requireNotNull(member.currentActiveLoans)
            val hasFines = requireNotNull(member.hasUnpaidOverdueFines)
            val reason = when {
                status != AccountStatus.APPROVED -> "Account is not approved"
                current >= maximum -> "Active loan limit reached"
                hasFines -> "Unpaid overdue fines"
                else -> null
            }
            MemberEligibility(
                memberId = requireNotNull(member.memberId).toString(),
                eligible = reason == null,
                status = status,
                maxActiveLoans = maximum,
                currentActiveLoans = current,
                hasUnpaidOverdueFines = hasFines,
                reason = reason,
                evaluatedAt = clock.instant(),
            )
        }

    fun findIdentityEvidence(memberId: UUID): IdentityEvidenceRef? = dsl
        .selectFrom(MEMBERSHIP_IDENTITY_EVIDENCE)
        .where(MEMBERSHIP_IDENTITY_EVIDENCE.MEMBER_ID.eq(memberId))
        .fetchOne()
        ?.let { evidence ->
            IdentityEvidenceRef(
                evidenceId = requireNotNull(evidence.evidenceId).toString(),
                memberId = requireNotNull(evidence.memberId).toString(),
                mimeType = requireNotNull(evidence.mimeType),
                fileSize = requireNotNull(evidence.fileSize),
                checksumSha256 = requireNotNull(evidence.checksumSha256).trim(),
                uploadedAt = requireNotNull(evidence.uploadedAt).toInstant(),
            )
        }
}
