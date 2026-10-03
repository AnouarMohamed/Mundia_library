package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.dto.DeadLetterReplay
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface DeadLetterReplayStore {
    fun replay(
        requestId: UUID,
        deliveryId: UUID,
        actorSubject: String,
        justification: String,
        queuedAt: Instant,
    ): DeadLetterReplay
}

@Service
class DeadLetterReplayService(
    private val store: DeadLetterReplayStore,
    private val clock: Clock,
) {
    fun replay(requestId: UUID, deliveryId: UUID, actorSubject: String, justification: String): DeadLetterReplay {
        require(actorSubject.isNotBlank() && actorSubject == actorSubject.trim() && actorSubject.length <= 200) {
            "JWT subject must be a non-blank canonical value of at most 200 characters"
        }
        require(actorSubject.none(Char::isISOControl)) { "JWT subject must not contain control characters" }
        val canonicalJustification = justification.trim()
        require(canonicalJustification.length in 20..500) { "Justification must contain 20 to 500 characters" }
        require(canonicalJustification.none(Char::isISOControl)) { "Justification must not contain control characters" }
        return store.replay(requestId, deliveryId, actorSubject, canonicalJustification, clock.instant())
    }
}

class DeadLetterReplayIdempotencyConflictException :
    RuntimeException("Idempotency key was already used for a different dead-letter replay request")

class DeadLetterDeliveryNotFoundException : RuntimeException("Email delivery was not found")

class DeadLetterDeliveryStateConflictException : RuntimeException("Email delivery is not dead-lettered")

class DeadLetterRecipientSuppressedException : RuntimeException("Recipient email delivery is suppressed")

class DeadLetterReplayUnsafeFailureException :
    RuntimeException("Dead-letter failure is ambiguous and cannot be replayed safely")

class DeadLetterReplayLimitExceededException : RuntimeException("Dead-letter replay limit has been reached")
