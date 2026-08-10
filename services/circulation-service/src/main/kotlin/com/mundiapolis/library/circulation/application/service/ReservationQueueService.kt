package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.ReservationCommandResult
import com.mundiapolis.library.circulation.application.model.ReservationOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.CirculationPolicyStore
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.ReservationOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.ReservationStore
import com.mundiapolis.library.circulation.domain.model.Copy
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.EditionId
import java.time.Instant

class ReservationQueueService(
    private val reservationStore: ReservationStore,
    private val copyStore: CopyStore,
    private val policyStore: CirculationPolicyStore,
    private val outboxEventStore: ReservationOutboxEventStore,
    private val identifierGenerator: IdentifierGenerator,
) {
    fun lockEdition(editionId: EditionId) = reservationStore.lockEdition(editionId)

    fun claimNewlyAvailableCopy(
        editionId: EditionId,
        copyId: CopyId,
        now: Instant,
        actorFingerprint: String,
    ): Copy {
        reservationStore.lockEdition(editionId)
        val waiting = reservationStore.lockOldestWaiting(editionId)
        if (waiting != null) {
            if (!copyStore.reserve(copyId, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            makeReady(waiting, copyId, now, actorFingerprint)
        }
        return copyStore.lockById(copyId) ?: throw ConcurrentCirculationUpdateException()
    }

    fun releaseReturnedCopy(
        editionId: EditionId,
        copyId: CopyId,
        now: Instant,
        actorFingerprint: String,
    ) {
        reservationStore.lockEdition(editionId)
        val waiting = reservationStore.lockOldestWaiting(editionId)
        if (waiting == null) {
            if (!copyStore.release(copyId, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            return
        }
        if (!copyStore.returnToReservation(copyId, now)) {
            throw ConcurrentCirculationUpdateException()
        }
        makeReady(waiting, copyId, now, actorFingerprint)
    }

    fun reassignOrReleaseReservedCopy(
        editionId: EditionId,
        copyId: CopyId,
        now: Instant,
        actorFingerprint: String,
    ) {
        reservationStore.lockEdition(editionId)
        val waiting = reservationStore.lockOldestWaiting(editionId)
        if (waiting == null) {
            if (!copyStore.releaseReserved(copyId, now)) {
                throw ConcurrentCirculationUpdateException()
            }
            return
        }
        makeReady(waiting, copyId, now, actorFingerprint)
    }

    private fun makeReady(
        waiting: com.mundiapolis.library.circulation.domain.model.Reservation,
        copyId: CopyId,
        now: Instant,
        actorFingerprint: String,
    ) {
        val ready = waiting.makeReady(copyId, now, policyStore.current().reservationHoldPeriod)
        if (!reservationStore.update(ready, waiting.version, now)) {
            throw ConcurrentCirculationUpdateException()
        }
        outboxEventStore.append(
            ReservationOutboxEvent(
                id = identifierGenerator.next(),
                result = ReservationCommandResult.from(ready),
                eventType = "circulation.reservation.ready",
                occurredAt = now,
                actorFingerprint = actorFingerprint,
            ),
        )
    }
}
