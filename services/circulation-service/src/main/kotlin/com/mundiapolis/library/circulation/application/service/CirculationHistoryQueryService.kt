package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.InvalidHistoryQueryException
import com.mundiapolis.library.circulation.application.model.MemberLoanPage
import com.mundiapolis.library.circulation.application.model.MemberReservationPage
import com.mundiapolis.library.circulation.application.port.inbound.GetMemberLoansQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetMemberReservationsQuery
import com.mundiapolis.library.circulation.application.port.outbound.CirculationHistoryReader
import com.mundiapolis.library.circulation.application.port.outbound.HistoryCursor
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class CirculationHistoryQueryService(
    private val reader: CirculationHistoryReader,
) : GetMemberLoansQuery, GetMemberReservationsQuery {
    override fun get(
        memberId: MemberId,
        status: LoanStatus?,
        limit: Int?,
        cursor: String?,
    ): MemberLoanPage {
        val pageSize = normalizeLimit(limit)
        val rows = reader.loans(memberId, status, decodeCursor(cursor), pageSize + 1)
        val items = rows.take(pageSize)
        return MemberLoanPage(
            memberId,
            items,
            rows.nextCursor(pageSize) { HistoryCursor(it.requestedAt, it.id.value) },
        )
    }

    override fun get(
        memberId: MemberId,
        status: ReservationStatus?,
        limit: Int?,
        cursor: String?,
    ): MemberReservationPage {
        val pageSize = normalizeLimit(limit)
        val rows = reader.reservations(memberId, status, decodeCursor(cursor), pageSize + 1)
        val items = rows.take(pageSize)
        return MemberReservationPage(
            memberId,
            items,
            rows.nextCursor(pageSize) { HistoryCursor(it.placedAt, it.id.value) },
        )
    }

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
            val cursor = HistoryCursor(
                Instant.parse(decoded.substring(0, separator)),
                UUID.fromString(decoded.substring(separator + 1)),
            )
            if (encodeCursor(cursor) != raw) throw InvalidHistoryQueryException()
            cursor
        } catch (_: RuntimeException) {
            throw InvalidHistoryQueryException()
        }
    }

    private fun encodeCursor(cursor: HistoryCursor): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${cursor.occurredAt}|${cursor.aggregateId}".toByteArray(StandardCharsets.UTF_8))

    private fun <T> List<T>.nextCursor(
        pageSize: Int,
        cursor: (T) -> HistoryCursor,
    ): String? = if (size > pageSize) encodeCursor(cursor(this[pageSize - 1])) else null

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_CURSOR_LENGTH = 160
    }
}
