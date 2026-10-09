package com.mundiapolis.library.circulation

import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapCommand
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapItem
import com.mundiapolis.library.circulation.application.model.MembershipEligibilityBootstrapResult
import com.mundiapolis.library.circulation.application.port.outbound.MembershipEligibilityBootstrapStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.service.MembershipEligibilityBootstrapService
import com.mundiapolis.library.circulation.domain.model.MemberEligibilityStatus
import com.mundiapolis.library.circulation.domain.model.MemberId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MembershipEligibilityBootstrapContractTest {
    @Test
    fun `canonical manifest remains compatible with the Kotlin cutover operator`() {
        val store = CapturingStore()
        val service = MembershipEligibilityBootstrapService(store, TimeProvider { COMPLETED_AT })
        service.bootstrap(
            MembershipEligibilityBootstrapCommand(
                BOOTSTRAP_ID,
                SOURCE_REVISION,
                listOf(
                    MembershipEligibilityBootstrapItem(
                        MemberId(MEMBER_ID),
                        MemberEligibilityStatus.ELIGIBLE,
                        null,
                        1,
                        OCCURRED_AT,
                        CONTENT_SHA256,
                    ),
                ),
                "a".repeat(64),
            ),
        )

        assertThat(store.manifestSha256)
            .isEqualTo("b8f34d20564cbd8e628d4585666448216c6d98160380c40c30af73ffc7cfc3c0")
    }

    private class CapturingStore : MembershipEligibilityBootstrapStore {
        lateinit var manifestSha256: String

        override fun bootstrap(
            command: MembershipEligibilityBootstrapCommand,
            manifestSha256: String,
            completedAt: Instant,
        ): MembershipEligibilityBootstrapResult {
            this.manifestSha256 = manifestSha256
            return MembershipEligibilityBootstrapResult(
                command.bootstrapId,
                command.sourceRevision,
                manifestSha256,
                command.items.size,
                completedAt,
                replayed = false,
            )
        }

        override fun receipt(bootstrapId: UUID): MembershipEligibilityBootstrapResult =
            error("Receipt read is outside this contract test")
    }

    private companion object {
        val BOOTSTRAP_ID: UUID = UUID.fromString("ecd98939-eb86-82b0-bec7-6effda1c5509")
        val MEMBER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OCCURRED_AT: Instant = Instant.parse("2026-10-08T00:00:01Z")
        val COMPLETED_AT: Instant = Instant.parse("2026-10-09T00:01:00Z")
        const val SOURCE_REVISION = "c1b0591a6c1b4ca336584c0a9211b0244c5a9d21bb6e6792386412e179246878"
        const val CONTENT_SHA256 = "cbcbca9f64ceea77124e98686a3bc8ec472ea1c6b9901bb5ae4f583423520c34"
    }
}
