package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.ClaimedMembershipOutboxEvent
import com.mundiapolis.library.membership.dto.EncodedMembershipEvent
import com.mundiapolis.library.membership.dto.MembershipBrokerAcknowledgement
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureCode
import com.mundiapolis.library.membership.dto.MembershipOutboxFailureDisposition
import com.mundiapolis.library.membership.dto.MembershipOutboxStatistics
import java.time.Instant

interface MembershipOutboxStore {
    fun claimBatch(
        owner: String,
        now: Instant,
        leaseExpiresAt: Instant,
        batchSize: Int,
    ): List<ClaimedMembershipOutboxEvent>

    fun markPublished(
        owner: String,
        event: ClaimedMembershipOutboxEvent,
        acknowledgement: MembershipBrokerAcknowledgement,
        publishedAt: Instant,
    ): Boolean

    fun recordFailure(
        owner: String,
        event: ClaimedMembershipOutboxEvent,
        code: MembershipOutboxFailureCode,
        failedAt: Instant,
        nextAttemptAt: Instant,
        maximumAttempts: Int,
        blockImmediately: Boolean,
    ): MembershipOutboxFailureDisposition

    fun deletePublishedBefore(cutoff: Instant, batchSize: Int): Int

    fun statistics(now: Instant): MembershipOutboxStatistics
}

fun interface MembershipEventEncoder {
    fun encode(event: ClaimedMembershipOutboxEvent): EncodedMembershipEvent
}

fun interface MembershipBrokerPublisher {
    fun publish(event: EncodedMembershipEvent): MembershipBrokerAcknowledgement
}
