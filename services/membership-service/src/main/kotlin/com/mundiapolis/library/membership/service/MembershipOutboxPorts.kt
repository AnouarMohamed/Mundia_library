package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.ClaimedMembershipOutboxEvent
import com.mundiapolis.library.membership.dto.EncodedMembershipEvent

fun interface MembershipEventEncoder {
    fun encode(event: ClaimedMembershipOutboxEvent): EncodedMembershipEvent
}
