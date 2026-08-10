package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.CirculationPolicyView
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationPolicyQuery
import com.mundiapolis.library.circulation.application.port.outbound.CirculationPolicyStore
import com.mundiapolis.library.circulation.domain.model.CirculationPolicy

class GetCirculationPolicyService(
    private val policyStore: CirculationPolicyStore,
) : GetCirculationPolicyQuery {
    override fun get(): CirculationPolicyView = policyStore.current().toView()
}

fun CirculationPolicy.toView(): CirculationPolicyView = CirculationPolicyView(
    revision = revisionId.toString(),
    sequence = sequence,
    defaultLoanPeriod = defaultLoanPeriod,
    renewalPeriod = renewalPeriod,
    maximumRenewals = maximumRenewals,
    fineCurrency = fineCurrency,
    reservationHoldPeriod = reservationHoldPeriod,
    maximumActiveReservations = maximumActiveReservations,
    effectiveAt = effectiveAt,
)
