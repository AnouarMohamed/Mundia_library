package com.mundiapolis.library.membership.adapter.outbound.persistence

import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_AUDIT_ENTRY
import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_COMMAND_IDEMPOTENCY
import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_MEMBER
import com.mundiapolis.library.membership.adapter.outbound.persistence.jooq.generated.Tables.MEMBERSHIP_OUTBOX_EVENT
import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.ChangeAccountStatusCommand
import com.mundiapolis.library.membership.dto.MembershipCommandConflictException
import com.mundiapolis.library.membership.dto.MembershipCommandExecution
import com.mundiapolis.library.membership.dto.MembershipCommandNotFoundException
import com.mundiapolis.library.membership.dto.MembershipCommandResult
import com.mundiapolis.library.membership.dto.MembershipIdempotencyConflictException
import com.mundiapolis.library.membership.dto.MembershipIdempotencyIncompleteException
import com.mundiapolis.library.membership.dto.InvalidMembershipActorException
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqMembershipCommandRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    fun changeAccountStatus(
        command: ChangeAccountStatusCommand,
        requestFingerprint: String,
        now: Instant,
    ): MembershipCommandExecution = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        executeIdempotently(tx, command, requestFingerprint, now) {
            tx.fetch("select pg_advisory_xact_lock(?)", STATUS_CHANGE_LOCK_ID)
            val member = tx.selectFrom(MEMBERSHIP_MEMBER)
                .where(MEMBERSHIP_MEMBER.MEMBER_ID.eq(command.memberId))
                .forUpdate()
                .fetchOne()
                ?: throw MembershipCommandNotFoundException()
            if (command.actorMemberId == command.memberId) {
                throw InvalidMembershipActorException("Operators cannot change their own account status")
            }
            val currentVersion = requireNotNull(member.aggregateVersion)
            if (currentVersion != command.expectedVersion) {
                throw MembershipCommandConflictException(
                    "Member version is $currentVersion, not ${command.expectedVersion}",
                )
            }
            val previousStatus = AccountStatus.valueOf(requireNotNull(member.accountStatus))
            if (previousStatus == command.status) {
                throw MembershipCommandConflictException("Member already has the requested status")
            }
            if (
                member.membershipRole == "ADMIN" &&
                previousStatus == AccountStatus.APPROVED &&
                command.status != AccountStatus.APPROVED
            ) {
                val approvedAdministrators = tx.fetchCount(
                    MEMBERSHIP_MEMBER,
                    MEMBERSHIP_MEMBER.MEMBERSHIP_ROLE.eq("ADMIN")
                        .and(MEMBERSHIP_MEMBER.ACCOUNT_STATUS.eq(AccountStatus.APPROVED.name)),
                )
                if (approvedAdministrators <= 1) {
                    throw MembershipCommandConflictException(
                        "The final approved administrator cannot be suspended",
                    )
                }
            }
            val nextVersion = Math.incrementExact(currentVersion)
            val updated = tx.update(MEMBERSHIP_MEMBER)
                .set(MEMBERSHIP_MEMBER.ACCOUNT_STATUS, command.status.name)
                .set(MEMBERSHIP_MEMBER.AGGREGATE_VERSION, nextVersion)
                .set(MEMBERSHIP_MEMBER.UPDATED_AT, now.toOffsetDateTime())
                .where(
                    MEMBERSHIP_MEMBER.MEMBER_ID.eq(command.memberId)
                        .and(MEMBERSHIP_MEMBER.AGGREGATE_VERSION.eq(currentVersion)),
                )
                .execute()
            check(updated == 1) { "Locked membership status update was lost" }

            val previousState = statusState(command.memberId, previousStatus)
            val resultingState = statusState(command.memberId, command.status)
            val eventPayload = eligibilityPayload(
                memberId = command.memberId,
                accountStatus = command.status,
                activeLoans = requireNotNull(member.currentActiveLoans),
                maximumLoans = requireNotNull(member.maxActiveLoans),
                hasOverdueFines = requireNotNull(member.hasUnpaidOverdueFines),
            )
            persistAuditAndOutbox(
                tx = tx,
                command = command,
                previousState = previousState,
                resultingState = resultingState,
                eventPayload = eventPayload,
                aggregateVersion = nextVersion,
                now = now,
            )
            MembershipCommandResult(command.memberId, nextVersion, command.status, now)
        }
    }

    private fun executeIdempotently(
        tx: DSLContext,
        command: ChangeAccountStatusCommand,
        requestFingerprint: String,
        now: Instant,
        action: () -> MembershipCommandResult,
    ): MembershipCommandExecution {
        val claimed = tx.insertInto(MEMBERSHIP_COMMAND_IDEMPOTENCY)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.OWNER_FINGERPRINT, command.ownerFingerprint)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.IDEMPOTENCY_KEY, command.idempotencyKey)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.OPERATION, OPERATION)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.REQUEST_FINGERPRINT, requestFingerprint)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.CREATED_AT, now.toOffsetDateTime())
            .set(
                MEMBERSHIP_COMMAND_IDEMPOTENCY.EXPIRES_AT,
                now.plusSeconds(IDEMPOTENCY_RETENTION_SECONDS).toOffsetDateTime(),
            )
            .onConflictDoNothing()
            .execute() == 1
        if (!claimed) {
            val stored = tx.selectFrom(MEMBERSHIP_COMMAND_IDEMPOTENCY)
                .where(
                    MEMBERSHIP_COMMAND_IDEMPOTENCY.OWNER_FINGERPRINT.eq(command.ownerFingerprint)
                        .and(MEMBERSHIP_COMMAND_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(command.idempotencyKey)),
                )
                .fetchOne()
                ?: throw MembershipIdempotencyIncompleteException()
            if (stored.operation != OPERATION || stored.requestFingerprint?.trim() != requestFingerprint) {
                throw MembershipIdempotencyConflictException()
            }
            if (stored.completedAt == null) {
                throw MembershipIdempotencyIncompleteException()
            }
            return MembershipCommandExecution(
                MembershipCommandResult(
                    memberId = requireNotNull(stored.aggregateId),
                    aggregateVersion = requireNotNull(stored.aggregateVersion),
                    status = AccountStatus.valueOf(requireNotNull(stored.resultingStatus)),
                    occurredAt = requireNotNull(stored.occurredAt).toInstant(),
                ),
                replayed = true,
            )
        }

        val result = action()
        val completed = tx.update(MEMBERSHIP_COMMAND_IDEMPOTENCY)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.AGGREGATE_ID, result.memberId)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.AGGREGATE_VERSION, result.aggregateVersion)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.RESULTING_STATUS, result.status.name)
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.OCCURRED_AT, result.occurredAt.toOffsetDateTime())
            .set(MEMBERSHIP_COMMAND_IDEMPOTENCY.COMPLETED_AT, now.toOffsetDateTime())
            .where(
                MEMBERSHIP_COMMAND_IDEMPOTENCY.OWNER_FINGERPRINT.eq(command.ownerFingerprint)
                    .and(MEMBERSHIP_COMMAND_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(command.idempotencyKey))
                    .and(MEMBERSHIP_COMMAND_IDEMPOTENCY.COMPLETED_AT.isNull),
            )
            .execute()
        check(completed == 1) { "Membership idempotency result was not persisted" }
        return MembershipCommandExecution(result, replayed = false)
    }

    private fun persistAuditAndOutbox(
        tx: DSLContext,
        command: ChangeAccountStatusCommand,
        previousState: Map<String, Any?>,
        resultingState: Map<String, Any?>,
        eventPayload: Map<String, Any?>,
        aggregateVersion: Long,
        now: Instant,
    ) {
        tx.insertInto(MEMBERSHIP_AUDIT_ENTRY)
            .set(MEMBERSHIP_AUDIT_ENTRY.AUDIT_ID, UUID.randomUUID())
            .set(MEMBERSHIP_AUDIT_ENTRY.MEMBER_ID, command.memberId)
            .set(MEMBERSHIP_AUDIT_ENTRY.AGGREGATE_VERSION, aggregateVersion)
            .set(MEMBERSHIP_AUDIT_ENTRY.OPERATION, OPERATION)
            .set(MEMBERSHIP_AUDIT_ENTRY.ACTOR_FINGERPRINT, command.ownerFingerprint)
            .set(MEMBERSHIP_AUDIT_ENTRY.REASON, command.reason)
            .set(
                MEMBERSHIP_AUDIT_ENTRY.PREVIOUS_STATE,
                JSON.valueOf(objectMapper.writeValueAsString(previousState)),
            )
            .set(
                MEMBERSHIP_AUDIT_ENTRY.RESULTING_STATE,
                JSON.valueOf(objectMapper.writeValueAsString(resultingState)),
            )
            .set(MEMBERSHIP_AUDIT_ENTRY.OCCURRED_AT, now.toOffsetDateTime())
            .execute()
        val eventId = UUID.randomUUID()
        val payload = eventPayload + mapOf(
            "eventId" to eventId.toString(),
            "eventType" to EVENT_TYPE,
            "eventVersion" to EVENT_VERSION,
            "aggregateVersion" to aggregateVersion,
            "occurredAt" to now.toString(),
        )
        val headers = mapOf(
            "contentType" to "application/json",
            "schema" to "$EVENT_TYPE.v1",
        )
        tx.insertInto(MEMBERSHIP_OUTBOX_EVENT)
            .set(MEMBERSHIP_OUTBOX_EVENT.EVENT_ID, eventId)
            .set(MEMBERSHIP_OUTBOX_EVENT.AGGREGATE_ID, command.memberId)
            .set(MEMBERSHIP_OUTBOX_EVENT.AGGREGATE_VERSION, aggregateVersion)
            .set(MEMBERSHIP_OUTBOX_EVENT.EVENT_TYPE, EVENT_TYPE)
            .set(MEMBERSHIP_OUTBOX_EVENT.EVENT_VERSION, EVENT_VERSION)
            .set(MEMBERSHIP_OUTBOX_EVENT.OCCURRED_AT, now.toOffsetDateTime())
            .set(MEMBERSHIP_OUTBOX_EVENT.PAYLOAD, JSON.valueOf(objectMapper.writeValueAsString(payload)))
            .set(MEMBERSHIP_OUTBOX_EVENT.HEADERS, JSON.valueOf(objectMapper.writeValueAsString(headers)))
            .set(MEMBERSHIP_OUTBOX_EVENT.CREATED_AT, now.toOffsetDateTime())
            .execute()
    }

    private fun statusState(memberId: UUID, status: AccountStatus): Map<String, Any?> = linkedMapOf(
        "memberId" to memberId.toString(),
        "accountStatus" to status.name,
    )

    private fun eligibilityPayload(
        memberId: UUID,
        accountStatus: AccountStatus,
        activeLoans: Int,
        maximumLoans: Int,
        hasOverdueFines: Boolean,
    ): Map<String, Any?> {
        val reasonCode = when {
            accountStatus != AccountStatus.APPROVED -> "ACCOUNT_NOT_APPROVED"
            activeLoans >= maximumLoans -> "ACTIVE_LOAN_LIMIT_REACHED"
            hasOverdueFines -> "UNPAID_OVERDUE_FINES"
            else -> null
        }
        return linkedMapOf(
            "memberId" to memberId.toString(),
            "status" to if (reasonCode == null) "ELIGIBLE" else "INELIGIBLE",
            "reasonCode" to reasonCode,
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val OPERATION = "CHANGE_ACCOUNT_STATUS"
        const val EVENT_TYPE = "membership.member.eligibility-changed"
        const val EVENT_VERSION = 1
        const val IDEMPOTENCY_RETENTION_SECONDS = 86_400L
        const val STATUS_CHANGE_LOCK_ID = 71_202_502
    }
}
