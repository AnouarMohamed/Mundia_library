package com.mundiapolis.library.circulation.application.port.outbound

import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapCommand
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapResult
import java.time.Instant
import java.util.UUID

interface MembershipEligibilityBootstrapStore {
    fun bootstrap(
        command: MembershipEligibilityBootstrapCommand,
        manifestSha256: String,
        completedAt: Instant,
    ): MembershipEligibilityBootstrapResult

    fun receipt(bootstrapId: UUID): MembershipEligibilityBootstrapResult
}
