package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.port.inbound.CirculationStatus
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationStatusQuery
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/circulation")
class CirculationStatusController(
    private val getCirculationStatus: GetCirculationStatusQuery,
) {
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('SCOPE_circulation.read')")
    fun status(): CirculationStatus = getCirculationStatus.getStatus()
}
