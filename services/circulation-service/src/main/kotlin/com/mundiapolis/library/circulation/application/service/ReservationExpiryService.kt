package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.ConcurrentCirculationUpdateException
import com.mundiapolis.library.circulation.application.model.ReservationCommandResult
import com.mundiapolis.library.circulation.application.model.ReservationOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.ReservationOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.ReservationStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.domain.model.ReservationId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.HexFormat

class ReservationExpiryService(
    private val transactionRunner: TransactionRunner,
    private val reservationStore: ReservationStore,
    private val queueService: ReservationQueueService,
    private val outboxEventStore: ReservationOutboxEventStore,
    private val timeProvider: TimeProvider,
    private val identifierGenerator: IdentifierGenerator,
) {
    fun findDue(batchSize: Int): List<ReservationId> =
        reservationStore.findExpiredIds(timeProvider.now(), batchSize)

    fun expireIfDue(id: ReservationId): Boolean = transactionRunner.required {
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val ready = reservationStore.lockById(id) ?: return@required false
        if (
            ready.status != ReservationStatus.READY ||
            now < requireNotNull(ready.expiresAt)
        ) {
            return@required false
        }
        reservationStore.lockEdition(ready.editionId)
        val expired = ready.expire(now)
        if (!reservationStore.update(expired, ready.version, now)) {
            throw ConcurrentCirculationUpdateException()
        }
        queueService.reassignOrReleaseReservedCopy(
            ready.editionId,
            requireNotNull(ready.copyId),
            now,
            SYSTEM_ACTOR_FINGERPRINT,
        )
        outboxEventStore.append(
            ReservationOutboxEvent(
                identifierGenerator.next(),
                ReservationCommandResult.from(expired),
                "circulation.reservation.expired",
                now,
                SYSTEM_ACTOR_FINGERPRINT,
            ),
        )
        true
    }

    private companion object {
        val SYSTEM_ACTOR_FINGERPRINT: String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest("system:reservation-expiry".toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
