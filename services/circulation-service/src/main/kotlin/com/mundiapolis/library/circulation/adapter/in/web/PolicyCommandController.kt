package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.InvalidCirculationPolicyException
import com.mundiapolis.library.circulation.application.model.UpdateCirculationPolicyValues
import com.mundiapolis.library.circulation.application.port.inbound.UpdateCirculationPolicyCommand
import com.mundiapolis.library.circulation.application.port.inbound.UpdateCirculationPolicyUseCase
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@RestController
@RequestMapping("/api/v1/circulation/policy")
class PolicyCommandController(
    private val updatePolicy: UpdateCirculationPolicyUseCase,
    private val principalResolver: JwtCommandPrincipalResolver,
) {
    @PutMapping
    @PreAuthorize("hasAuthority('SCOPE_circulation.policy.manage')")
    fun update(
        authentication: JwtAuthenticationToken,
        @RequestHeader("Idempotency-Key") rawIdempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
        @RequestBody request: UpdateCirculationPolicyRequest,
    ): ResponseEntity<CirculationPolicyResponse> {
        val execution = updatePolicy.update(
            UpdateCirculationPolicyCommand(
                expectedRevision.trim('"'),
                request.toValues(),
                IdempotencyKey.parse(rawIdempotencyKey),
                principalResolver.forAdministrativeCommand(authentication),
            ),
        )
        return ResponseEntity.ok()
            .eTag(execution.result.revision)
            .header("Idempotency-Replayed", execution.replayed.toString())
            .body(CirculationPolicyResponse.from(execution.result))
    }
}

data class UpdateCirculationPolicyRequest(
    val defaultLoanPeriod: String,
    val renewalPeriod: String,
    val maximumRenewals: Int,
    val fineCurrency: String,
    val reservationHoldPeriod: String,
    val maximumActiveReservations: Int,
) {
    fun toValues(): UpdateCirculationPolicyValues = try {
        UpdateCirculationPolicyValues(
            Duration.parse(defaultLoanPeriod),
            Duration.parse(renewalPeriod),
            maximumRenewals,
            fineCurrency,
            Duration.parse(reservationHoldPeriod),
            maximumActiveReservations,
        )
    } catch (_: RuntimeException) {
        throw InvalidCirculationPolicyException("Policy durations must use ISO-8601 syntax")
    }
}
