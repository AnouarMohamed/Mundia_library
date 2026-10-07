package com.mundiapolis.library.membership.service.impl

import com.mundiapolis.library.membership.adapter.outbound.persistence.JooqMembershipRepository
import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.AdminMemberPage
import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import com.mundiapolis.library.membership.service.MembershipService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

@Service
class MembershipServiceImpl(
    private val repository: JooqMembershipRepository,
    private val clock: Clock,
) : MembershipService {
    override fun listMembersForAdministration(
        status: AccountStatus,
        limit: Int?,
        cursor: String?,
    ): AdminMemberPage {
        val pageSize = limit ?: DEFAULT_ADMIN_PAGE_SIZE
        require(pageSize in 1..MAX_ADMIN_PAGE_SIZE) { "limit must be between 1 and $MAX_ADMIN_PAGE_SIZE" }
        val position = cursor?.let(::decodeCursor)
        val records = repository.findMembersForAdministration(
            status,
            position?.first,
            position?.second,
            pageSize + 1,
        )
        val items = records.take(pageSize)
        val nextCursor = if (records.size > pageSize) items.last().let {
            encodeCursor(it.createdAt, it.memberId)
        } else {
            null
        }
        return AdminMemberPage(items, nextCursor)
    }

    override fun getMemberProfile(memberId: String): MemberProfile? =
        repository.findProfile(memberId.toMemberId())

    override fun checkEligibility(memberId: String): MemberEligibility =
        repository.findEligibility(memberId.toMemberId()) ?: MemberEligibility(
            memberId = memberId,
            eligible = false,
            status = AccountStatus.REJECTED,
            maxActiveLoans = 0,
            currentActiveLoans = 0,
            hasUnpaidOverdueFines = false,
            reason = "Member not found",
            evaluatedAt = clock.instant(),
        )

    override fun getIdentityEvidenceRef(memberId: String): IdentityEvidenceRef? =
        repository.findIdentityEvidence(memberId.toMemberId())

    private fun String.toMemberId(): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse { throw IllegalArgumentException("memberId must be a canonical UUID") }

    private fun encodeCursor(createdAt: Instant, memberId: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "$createdAt|$memberId".toByteArray(StandardCharsets.UTF_8),
        )

    private fun decodeCursor(cursor: String): Pair<Instant, UUID> {
        require(cursor.length in 16..200 && CURSOR.matches(cursor)) { "cursor is invalid" }
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("cursor is invalid") }
        val fields = decoded.split('|')
        require(fields.size == 2) { "cursor is invalid" }
        val createdAt = runCatching { Instant.parse(fields[0]) }.getOrNull()
        val memberId = runCatching { UUID.fromString(fields[1]) }.getOrNull()
        require(createdAt != null && memberId != null && memberId.toString() == fields[1]) { "cursor is invalid" }
        return createdAt to memberId
    }

    private companion object {
        const val DEFAULT_ADMIN_PAGE_SIZE = 25
        const val MAX_ADMIN_PAGE_SIZE = 100
        val CURSOR = Regex("[A-Za-z0-9_-]+")
    }
}
