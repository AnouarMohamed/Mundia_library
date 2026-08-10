package com.mundiapolis.library.circulation.application.port.inbound

import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.PolicyCommandExecution
import com.mundiapolis.library.circulation.application.model.UpdateCirculationPolicyValues

data class UpdateCirculationPolicyCommand(
    val expectedRevision: String,
    val values: UpdateCirculationPolicyValues,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

fun interface UpdateCirculationPolicyUseCase {
    fun update(command: UpdateCirculationPolicyCommand): PolicyCommandExecution
}
