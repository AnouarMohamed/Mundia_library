package com.mundiapolis.library.circulation.application.service

import com.mundiapolis.library.circulation.application.model.CirculationPolicyView
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationPolicyQuery
import com.mundiapolis.library.circulation.config.CirculationPolicyProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

class GetCirculationPolicyService(
    policy: CirculationPolicyProperties,
) : GetCirculationPolicyQuery {
    private val view = CirculationPolicyView(
        revision = revision(policy),
        defaultLoanPeriod = policy.defaultLoanPeriod,
        renewalPeriod = policy.renewalPeriod,
        maximumRenewals = policy.maximumRenewals,
        fineCurrency = policy.fineCurrency,
    )

    override fun get(): CirculationPolicyView = view

    private fun revision(policy: CirculationPolicyProperties): String {
        val canonical = listOf(
            "circulation-policy-v1",
            policy.defaultLoanPeriod.toString(),
            policy.renewalPeriod.toString(),
            policy.maximumRenewals.toString(),
            policy.fineCurrency,
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
