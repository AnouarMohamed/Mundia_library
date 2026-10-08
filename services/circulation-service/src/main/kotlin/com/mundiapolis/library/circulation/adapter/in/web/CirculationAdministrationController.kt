package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.port.inbound.GetAdministrativeCirculationOverviewQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetAdministrativeLoanQueueQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetAdministrativeReservationQueueQuery
import com.mundiapolis.library.circulation.domain.model.LoanStatus
import com.mundiapolis.library.circulation.domain.model.ReservationStatus
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/circulation/admin")
class CirculationAdministrationController(
    private val overview: GetAdministrativeCirculationOverviewQuery,
    private val loans: GetAdministrativeLoanQueueQuery,
    private val reservations: GetAdministrativeReservationQueueQuery,
) {
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('SCOPE_circulation.admin.read')")
    fun overview(): ResponseEntity<AdministrativeCirculationOverviewResponse> {
        val snapshot = overview.get()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AdministrativeCirculationOverviewResponse(
                snapshot.requestedLoans,
                snapshot.activeLoans,
                snapshot.overdueLoans,
                snapshot.waitingReservations,
                snapshot.readyReservations,
            ),
        )
    }

    @GetMapping("/loans")
    @PreAuthorize("hasAuthority('SCOPE_circulation.admin.read')")
    fun loans(
        @RequestParam(defaultValue = "REQUESTED") status: LoanStatus,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<AdministrativeLoanPageResponse> {
        val page = loans.get(status, limit, cursor)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AdministrativeLoanPageResponse(page.items.map(LoanHistoryItemResponse::from), page.nextCursor),
        )
    }

    @GetMapping("/reservations")
    @PreAuthorize("hasAuthority('SCOPE_circulation.admin.read')")
    fun reservations(
        @RequestParam(defaultValue = "READY") status: ReservationStatus,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<AdministrativeReservationPageResponse> {
        val page = reservations.get(status, limit, cursor)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AdministrativeReservationPageResponse(
                page.items.map { ReservationCommandResponse.from(com.mundiapolis.library.circulation.application.model.ReservationCommandResult.from(it)) },
                page.nextCursor,
            ),
        )
    }
}

data class AdministrativeCirculationOverviewResponse(
    val requestedLoans: Long,
    val activeLoans: Long,
    val overdueLoans: Long,
    val waitingReservations: Long,
    val readyReservations: Long,
)

data class AdministrativeLoanPageResponse(
    val items: List<LoanHistoryItemResponse>,
    val nextCursor: String?,
)

data class AdministrativeReservationPageResponse(
    val items: List<ReservationCommandResponse>,
    val nextCursor: String?,
)
