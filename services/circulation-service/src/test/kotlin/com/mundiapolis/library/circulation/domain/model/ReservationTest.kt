package com.mundiapolis.library.circulation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ReservationTest {
    private val placedAt = Instant.parse("2026-08-10T10:00:00Z")

    @Test
    fun `waiting ready fulfilled lifecycle preserves hold invariants`() {
        val copyId = CopyId(UUID.randomUUID())
        val ready = reservation().makeReady(copyId, placedAt.plusSeconds(1), Duration.ofHours(48))
        val fulfilled = ready.fulfill(placedAt.plusSeconds(60))

        assertThat(ready.status).isEqualTo(ReservationStatus.READY)
        assertThat(ready.copyId).isEqualTo(copyId)
        assertThat(ready.version).isEqualTo(1)
        assertThat(fulfilled.status).isEqualTo(ReservationStatus.FULFILLED)
        assertThat(fulfilled.version).isEqualTo(2)
    }

    @Test
    fun `expired holds cannot be fulfilled or expired early`() {
        val ready = reservation().makeReady(
            CopyId(UUID.randomUUID()),
            placedAt,
            Duration.ofHours(1),
        )

        assertThatThrownBy { ready.expire(placedAt.plusSeconds(3599)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ready.fulfill(placedAt.plusSeconds(3601)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ready.expire(placedAt.plusSeconds(3600)).status)
            .isEqualTo(ReservationStatus.EXPIRED)
    }

    private fun reservation(): Reservation = Reservation.place(
        ReservationId(UUID.randomUUID()),
        MemberId(UUID.randomUUID()),
        EditionId(UUID.randomUUID()),
        placedAt,
    )
}
