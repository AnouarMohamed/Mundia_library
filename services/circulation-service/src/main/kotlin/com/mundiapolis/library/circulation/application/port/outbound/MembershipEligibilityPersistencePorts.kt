package com.mundiapolis.library.circulation.application.port.outbound

import com.mundiapolis.library.circulation.application.model.ProcessedConsumerEvent
import com.mundiapolis.library.circulation.domain.model.MemberEligibility
import com.mundiapolis.library.circulation.domain.model.MemberId
import java.time.Instant
import java.util.UUID

interface MemberEligibilityStore {
    fun lockMember(memberId: MemberId)

    fun find(memberId: MemberId): MemberEligibility?

    fun save(
        eligibility: MemberEligibility,
        expectedSourceVersion: Long?,
        now: Instant,
    ): Boolean
}

interface ConsumerInboxStore {
    fun find(consumerName: String, eventId: UUID): ProcessedConsumerEvent?

    fun append(event: ProcessedConsumerEvent): Boolean
}
