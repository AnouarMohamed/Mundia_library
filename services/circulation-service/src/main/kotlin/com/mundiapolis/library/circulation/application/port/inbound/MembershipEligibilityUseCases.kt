package com.mundiapolis.library.circulation.application.port.inbound

import com.mundiapolis.library.circulation.application.model.CirculationPolicyView
import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.EligibilityEventExecution
import com.mundiapolis.library.circulation.application.model.MemberEligibilityView
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityEvent
import com.mundiapolis.library.circulation.domain.model.MemberId

fun interface ApplyMembershipEligibilityEventUseCase {
    fun apply(event: MembershipEligibilityEvent): EligibilityEventExecution
}

fun interface GetMemberEligibilityQuery {
    fun get(memberId: MemberId, principal: CommandPrincipal): MemberEligibilityView
}

fun interface GetCirculationPolicyQuery {
    fun get(): CirculationPolicyView
}
