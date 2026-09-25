package com.mundiapolis.library.membership.dto

import java.time.Instant
import java.util.UUID

data class ChangeAccountStatusCommand(
    val memberId: UUID,
    val expectedVersion: Long,
    val status: AccountStatus,
    val reason: String,
    val idempotencyKey: String,
    val ownerFingerprint: String,
    val actorMemberId: UUID,
)

data class MembershipCommandResult(
    val memberId: UUID,
    val aggregateVersion: Long,
    val status: AccountStatus,
    val occurredAt: Instant,
)

data class MembershipCommandExecution(
    val result: MembershipCommandResult,
    val replayed: Boolean,
)

class MembershipCommandConflictException(message: String) : RuntimeException(message)

class MembershipCommandNotFoundException : RuntimeException("Member does not exist")

class MembershipIdempotencyConflictException :
    RuntimeException("Idempotency key was already used for different membership input")

class MembershipIdempotencyIncompleteException :
    RuntimeException("The prior membership command has no completed response")

class InvalidMembershipCommandException(message: String) : RuntimeException(message)

class InvalidMembershipActorException(message: String) : RuntimeException(message)
