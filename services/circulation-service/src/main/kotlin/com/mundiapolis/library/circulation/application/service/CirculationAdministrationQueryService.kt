package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.AdministrativeLoanPage
import com.mundiapolis.library.circulation.application.model.AdministrativeReservationPage
import com.mundiapolis.library.circulation.application.model.InvalidHistoryQueryException
import com.mundiapolis.library.circulation.application.port.inbound.GetAdministrativeCirculationOverviewQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetAdministrativeLoanQueueQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetAdministrativeReservationQueueQuery
import com.mundiapolis.library.circulation.application.port.outbound.AdministrativeCirculationSnapshot
import com.mundiapolis.library.circulation.application.port.outbound.CirculationHistoryReader
import com.mundiapolis.library.circulation.application.port.outbound.CirculationStatisticsPort
import com.mundiapolis.library.circulation.application.port.outbound.HistoryCursor
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class CirculationAdministrationQueryService(
    private val history: CirculationHistoryReader,
    private val statistics: CirculationStatisticsPort,
) : GetAdministrativeLoanQueueQuery,
    GetAdministrativeReservationQueueQuery,
    GetAdministrativeCirculationOverviewQuery {
    override fun get(
        status: LoanStatus,
        limit: Int?,
        cursor: String?,
    ): AdministrativeLoanPage {
        val pageSize = normalizeLimit(limit)
        val rows = history.administrativeLoans(status, decodeCursor(cursor), pageSize + 1)
        return AdministrativeLoanPage(
            rows.take(pageSize),
            rows.nextCursor(pageSize) { HistoryCursor(it.requestedAt, it.id.value) },
        )
    }

    override fun get(
        status: ReservationStatus,
        limit: Int?,
        cursor: String?,
    ): AdministrativeReservationPage {
        val pageSize = normalizeLimit(limit)
        val rows = history.administrativeReservations(status, decodeCursor(cursor), pageSize + 1)
        return AdministrativeReservationPage(
            rows.take(pageSize),
            rows.nextCursor(pageSize) { HistoryCursor(it.placedAt, it.id.value) },
        )
    }

    override fun get(): AdministrativeCirculationSnapshot = statistics.administrativeSnapshot()

    private fun normalizeLimit(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).also {
        if (it !in 1..MAX_LIMIT) throw InvalidHistoryQueryException()
    }

    private fun decodeCursor(raw: String?): HistoryCursor? {
        if (raw == null) return null
        if (raw.isBlank() || raw.length > MAX_CURSOR_LENGTH) throw InvalidHistoryQueryException()
        return try {
            val decoded = String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8)
            val separator = decoded.lastIndexOf('|')
            if (separator <= 0 || separator == decoded.lastIndex) throw InvalidHistoryQueryException()
            HistoryCursor(
                Instant.parse(decoded.substring(0, separator)),
                UUID.fromString(decoded.substring(separator + 1)),
            ).also { if (encodeCursor(it) != raw) throw InvalidHistoryQueryException() }
        } catch (_: RuntimeException) {
            throw InvalidHistoryQueryException()
        }
    }

    private fun encodeCursor(cursor: HistoryCursor): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${cursor.occurredAt}|${cursor.aggregateId}".toByteArray(StandardCharsets.UTF_8))

    private fun <T> List<T>.nextCursor(pageSize: Int, cursor: (T) -> HistoryCursor): String? =
        if (size > pageSize) encodeCursor(cursor(this[pageSize - 1])) else null

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val MAX_CURSOR_LENGTH = 160
    }
}
