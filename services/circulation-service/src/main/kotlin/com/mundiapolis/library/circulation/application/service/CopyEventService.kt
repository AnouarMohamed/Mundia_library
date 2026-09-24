package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.InventoryCommandResult
import com.mundiapolis.library.circulation.application.model.InventoryOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.InventoryOutboxEventStore
import com.mundiapolis.library.circulation.domain.model.Copy
import com.mundiapolis.library.circulation.domain.model.CopyId
import java.time.Instant

class CopyEventService(
    private val copyStore: CopyStore,
    private val outboxEventStore: InventoryOutboxEventStore,
    private val identifierGenerator: IdentifierGenerator,
) {
    fun appendCurrent(
        copyId: CopyId,
        occurredAt: Instant,
        actorFingerprint: String,
        reason: String,
    ): Copy {
        val copy = copyStore.lockById(copyId) ?: throw ConcurrentCirculationUpdateException()
        append(copy, STATUS_CHANGED_EVENT, occurredAt, actorFingerprint, reason)
        return copy
    }

    fun append(
        copy: Copy,
        eventType: String,
        occurredAt: Instant,
        actorFingerprint: String,
        reason: String,
    ) {
        outboxEventStore.append(
            InventoryOutboxEvent(
                id = identifierGenerator.next(),
                aggregateId = copy.id,
                aggregateVersion = copy.version,
                eventType = eventType,
                eventVersion = 1,
                occurredAt = occurredAt,
                result = InventoryCommandResult.from(copy, occurredAt),
                actorFingerprint = actorFingerprint,
                reason = reason,
            ),
        )
    }

    companion object {
        const val STATUS_CHANGED_EVENT = "circulation.copy.status-changed"
    }
}
