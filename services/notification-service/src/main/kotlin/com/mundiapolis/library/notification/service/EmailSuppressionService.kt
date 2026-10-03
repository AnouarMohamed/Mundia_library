package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.dto.EmailSuppressionRemoval
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface EmailSuppressionStore {
    fun remove(
        requestId: UUID,
        memberId: UUID,
        actorSubject: String,
        justification: String,
        performedAt: Instant,
    ): EmailSuppressionRemoval
}

@Service
class EmailSuppressionService(
    private val store: EmailSuppressionStore,
    private val clock: Clock,
) {
    fun remove(requestId: UUID, memberId: UUID, actorSubject: String, justification: String): EmailSuppressionRemoval {
        require(actorSubject.isNotBlank() && actorSubject == actorSubject.trim() && actorSubject.length <= 200) {
            "JWT subject must be a non-blank canonical value of at most 200 characters"
        }
        require(actorSubject.none(Char::isISOControl)) { "JWT subject must not contain control characters" }
        val canonicalJustification = justification.trim()
        require(canonicalJustification.length in 20..500) { "Justification must contain 20 to 500 characters" }
        require(canonicalJustification.none(Char::isISOControl)) { "Justification must not contain control characters" }
        return store.remove(requestId, memberId, actorSubject, canonicalJustification, clock.instant())
    }
}

class EmailSuppressionRemovalConflictException :
    RuntimeException("Idempotency key was already used for a different suppression-removal request")
