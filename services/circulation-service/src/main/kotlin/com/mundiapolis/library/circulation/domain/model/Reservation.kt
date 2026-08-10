package com.mundiapolis.library.circulation.domain.model

import java.time.Duration
import java.time.Instant

enum class ReservationStatus {
    WAITING,
    READY,
    FULFILLED,
    CANCELLED,
    EXPIRED,
}

data class Reservation private constructor(
    val id: ReservationId,
    val memberId: MemberId,
    val editionId: EditionId,
    val copyId: CopyId?,
    val status: ReservationStatus,
    val placedAt: Instant,
    val readyAt: Instant?,
    val expiresAt: Instant?,
    val fulfilledAt: Instant?,
    val cancelledAt: Instant?,
    val version: Long,
) {
    init {
        require(version >= 0) { "Reservation version cannot be negative" }
        requireConsistentState()
    }

    fun makeReady(copyId: CopyId, now: Instant, holdPeriod: Duration): Reservation {
        require(status == ReservationStatus.WAITING) { "Only a waiting reservation can become ready" }
        require(!holdPeriod.isZero && !holdPeriod.isNegative) { "Hold period must be positive" }
        return copy(
            copyId = copyId,
            status = ReservationStatus.READY,
            readyAt = now,
            expiresAt = now.plus(holdPeriod),
            version = version + 1,
        )
    }

    fun fulfill(now: Instant): Reservation {
        require(status == ReservationStatus.READY) { "Only a ready reservation can be fulfilled" }
        require(now <= requireNotNull(expiresAt)) { "An expired reservation cannot be fulfilled" }
        return copy(
            status = ReservationStatus.FULFILLED,
            fulfilledAt = now,
            version = version + 1,
        )
    }

    fun cancel(now: Instant): Reservation {
        require(status == ReservationStatus.WAITING || status == ReservationStatus.READY) {
            "Only an open reservation can be cancelled"
        }
        return copy(
            status = ReservationStatus.CANCELLED,
            cancelledAt = now,
            version = version + 1,
        )
    }

    fun expire(now: Instant): Reservation {
        require(status == ReservationStatus.READY) { "Only a ready reservation can expire" }
        require(now >= requireNotNull(expiresAt)) { "Reservation hold has not expired" }
        return copy(status = ReservationStatus.EXPIRED, version = version + 1)
    }

    private fun requireConsistentState() {
        when (status) {
            ReservationStatus.WAITING -> require(
                copyId == null && readyAt == null && expiresAt == null &&
                    fulfilledAt == null && cancelledAt == null,
            ) { "Waiting reservation state is inconsistent" }

            ReservationStatus.READY -> require(
                copyId != null && readyAt != null && expiresAt != null &&
                    readyAt >= placedAt && expiresAt > readyAt &&
                    fulfilledAt == null && cancelledAt == null,
            ) { "Ready reservation state is inconsistent" }

            ReservationStatus.FULFILLED -> require(
                copyId != null && readyAt != null && expiresAt != null &&
                    readyAt >= placedAt && fulfilledAt != null &&
                    fulfilledAt >= readyAt && fulfilledAt <= expiresAt && cancelledAt == null,
            ) { "Fulfilled reservation state is inconsistent" }

            ReservationStatus.CANCELLED -> require(
                fulfilledAt == null && cancelledAt != null && cancelledAt >= placedAt &&
                    (
                        (copyId == null && readyAt == null && expiresAt == null) ||
                            (copyId != null && readyAt != null && expiresAt != null &&
                                readyAt >= placedAt && expiresAt > readyAt)
                    ),
            ) { "Cancelled reservation state is inconsistent" }

            ReservationStatus.EXPIRED -> require(
                copyId != null && readyAt != null && expiresAt != null &&
                    readyAt >= placedAt && expiresAt > readyAt &&
                    fulfilledAt == null && cancelledAt == null,
            ) { "Expired reservation state is inconsistent" }
        }
    }

    companion object {
        fun place(
            id: ReservationId,
            memberId: MemberId,
            editionId: EditionId,
            placedAt: Instant,
        ): Reservation = Reservation(
            id = id,
            memberId = memberId,
            editionId = editionId,
            copyId = null,
            status = ReservationStatus.WAITING,
            placedAt = placedAt,
            readyAt = null,
            expiresAt = null,
            fulfilledAt = null,
            cancelledAt = null,
            version = 0,
        )

        fun restore(
            id: ReservationId,
            memberId: MemberId,
            editionId: EditionId,
            copyId: CopyId?,
            status: ReservationStatus,
            placedAt: Instant,
            readyAt: Instant?,
            expiresAt: Instant?,
            fulfilledAt: Instant?,
            cancelledAt: Instant?,
            version: Long,
        ): Reservation = Reservation(
            id,
            memberId,
            editionId,
            copyId,
            status,
            placedAt,
            readyAt,
            expiresAt,
            fulfilledAt,
            cancelledAt,
            version,
        )
    }
}
