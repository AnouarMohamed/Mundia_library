package com.mundiapolis.library.migration.eligibility

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import tools.jackson.databind.json.JsonMapper

class EligibilityBootstrapOperatorTest {
    @Test
    fun `item canonical contract is stable across the service boundary`() {
        val fixture = Fixture(memberCount = 1)
        val item = fixture.items.single()
        assertThat(item.contentSha256)
            .isEqualTo("cbcbca9f64ceea77124e98686a3bc8ec472ea1c6b9901bb5ae4f583423520c34")
        assertThat(EligibilityIntegrity.sourceRevision(listOf(item)))
            .isEqualTo("c1b0591a6c1b4ca336584c0a9211b0244c5a9d21bb6e6792386412e179246878")
        val bootstrapId = EligibilityIntegrity.bootstrapId(SNAPSHOT_ID, 0)
        assertThat(bootstrapId.toString()).isEqualTo("ecd98939-eb86-82b0-bec7-6effda1c5509")
        assertThat(
            EligibilityIntegrity.bootstrapManifest(
                bootstrapId,
                EligibilityIntegrity.sourceRevision(listOf(item)),
                listOf(item),
            ),
        ).isEqualTo("b8f34d20564cbd8e628d4585666448216c6d98160380c40c30af73ffc7cfc3c0")
    }

    @Test
    fun `apply verifies the source and reconciles deterministic bounded batches`() {
        val fixture = Fixture(memberCount = 205)
        val target = RecordingCirculationClient()
        val evidence = EligibilityBootstrapOperator(fixture.source, target, target, CLOCK).execute(
            OperatorCommand(SNAPSHOT_ID, batchSize = 100, parityConcurrency = 8, mode = OperatorMode.APPLY),
        )

        assertThat(evidence.mode).isEqualTo("APPLY")
        assertThat(evidence.schemaVersion).isEqualTo(3)
        assertThat(evidence.memberCount).isEqualTo(205)
        assertThat(evidence.batches).hasSize(3)
        assertThat(evidence.batches.map { it.memberCount }).containsExactly(100, 100, 5)
        assertThat(evidence.batches).allMatch { it.applied && it.replayed == false }
        assertThat(evidence.parity).isEqualTo(ParityEvidence(205, fixture.sourceRevision))
        assertThat(target.requests.keys).containsExactlyElementsOf(
            (0..2).map { EligibilityIntegrity.bootstrapId(SNAPSHOT_ID, it) },
        )
        assertThat(target.receiptReads).isEqualTo(3)
    }

    @Test
    fun `dry run verifies every digest without contacting circulation`() {
        val fixture = Fixture(memberCount = 4)
        val evidence = EligibilityBootstrapOperator(fixture.source, null, null, CLOCK).execute(
            OperatorCommand(SNAPSHOT_ID, batchSize = 2, parityConcurrency = 8, mode = OperatorMode.DRY_RUN),
        )

        assertThat(evidence.mode).isEqualTo("DRY_RUN")
        assertThat(evidence.batches).hasSize(2)
        assertThat(evidence.batches).allMatch { !it.applied && it.replayed == null }
        assertThat(evidence.parity).isNull()
    }

    @Test
    fun `corrupt source item fails closed before target mutation`() {
        val fixture = Fixture(memberCount = 2)
        fixture.items[0] = fixture.items[0].copy(contentSha256 = "0".repeat(64))
        val target = RecordingCirculationClient()

        assertThatThrownBy {
            EligibilityBootstrapOperator(fixture.source, target, target, CLOCK).execute(
                OperatorCommand(SNAPSHOT_ID, batchSize = 100, parityConcurrency = 8, mode = OperatorMode.APPLY),
            )
        }.isInstanceOf(OperatorValidationException::class.java)
            .hasMessage("Snapshot item digest is invalid")
        assertThat(target.requests).isEmpty()
    }

    @Test
    fun `apply fails closed when the post-bootstrap projection differs`() {
        val fixture = Fixture(memberCount = 2)
        val target = RecordingCirculationClient().apply { corruptMemberId = fixture.items.last().memberId }

        assertThatThrownBy {
            EligibilityBootstrapOperator(fixture.source, target, target, CLOCK).execute(
                OperatorCommand(SNAPSHOT_ID, batchSize = 100, parityConcurrency = 8, mode = OperatorMode.APPLY),
            )
        }.isInstanceOf(OperatorValidationException::class.java)
            .hasMessage("Circulation eligibility projection differs from the source snapshot")
    }

    @Test
    fun `parity mode verifies an existing projection without bootstrap authority or mutation`() {
        val fixture = Fixture(memberCount = 17)
        val target = RecordingCirculationClient().apply { seed(fixture.items) }

        val evidence = EligibilityBootstrapOperator(fixture.source, null, target, CLOCK).execute(
            OperatorCommand(SNAPSHOT_ID, batchSize = 10, parityConcurrency = 4, mode = OperatorMode.PARITY),
        )

        assertThat(evidence.mode).isEqualTo("PARITY")
        assertThat(evidence.parityConcurrency).isEqualTo(4)
        assertThat(evidence.parity).isEqualTo(ParityEvidence(17, fixture.sourceRevision))
        assertThat(evidence.batches).allMatch { !it.applied && it.replayed == null }
        assertThat(target.bootstrapCalls).isZero()
    }

    @Test
    fun `cli parser is explicit and rejects unknown arguments`() {
        val parsed = CliOptions.parse(
            arrayOf(
                "--snapshot-id", SNAPSHOT_ID.toString(),
                "--evidence-file", "/secure/evidence.json",
                "--batch-size", "25",
                "--parity-concurrency", "4",
                "--apply",
            ),
        )
        assertThat(parsed.batchSize).isEqualTo(25)
        assertThat(parsed.parityConcurrency).isEqualTo(4)
        assertThat(parsed.mode).isEqualTo(OperatorMode.APPLY)

        assertThat(
            CliOptions.parse(
                arrayOf(
                    "--snapshot-id", SNAPSHOT_ID.toString(),
                    "--evidence-file", "/secure/parity.json",
                    "--verify-parity",
                ),
            ).mode,
        ).isEqualTo(OperatorMode.PARITY)

        assertThatThrownBy {
            CliOptions.parse(
                arrayOf(
                    "--snapshot-id", SNAPSHOT_ID.toString(),
                    "--evidence-file", "/secure/evidence.json",
                    "--apply",
                    "--verify-parity",
                ),
            )
        }.isInstanceOf(OperatorValidationException::class.java)
            .hasMessage("--apply and --verify-parity are mutually exclusive")

        assertThatThrownBy { CliOptions.parse(arrayOf("--token", "must-not-be-an-argument")) }
            .isInstanceOf(OperatorValidationException::class.java)
    }

    @Test
    fun `remote clients require HTTPS except for explicit loopback drills`() {
        val transport = BoundedJsonTransport(JsonMapper.builder().build())
        assertThatThrownBy {
            HttpMembershipSnapshotClient("http://membership.example", TOKEN, true, transport)
        }.isInstanceOf(OperatorValidationException::class.java)
        assertThatThrownBy {
            HttpMembershipSnapshotClient("https://user@membership.example", TOKEN, false, transport)
        }.isInstanceOf(OperatorValidationException::class.java)
        assertThatThrownBy {
            HttpMembershipSnapshotClient("http://127.0.0.1:8080", TOKEN, false, transport)
        }.isInstanceOf(OperatorValidationException::class.java)

        HttpMembershipSnapshotClient("http://127.0.0.1:8080", TOKEN, true, transport)
    }

    private class Fixture(memberCount: Int) {
        val items: MutableList<SnapshotItem> = (1..memberCount).map { index ->
            val memberId = UUID(0, index.toLong())
            val raw = SnapshotItem(
                memberId,
                if (index % 2 == 0) EligibilityStatus.INELIGIBLE else EligibilityStatus.ELIGIBLE,
                if (index % 2 == 0) "ACTIVE_LOAN_LIMIT_REACHED" else null,
                index.toLong(),
                OCCURRED_AT.plusSeconds(index.toLong()),
                "",
            )
            raw.copy(contentSha256 = EligibilityIntegrity.itemHash(raw))
        }.toMutableList()
        val sourceRevision: String = EligibilityIntegrity.sourceRevision(items)
        val source = FakeMembershipClient(items)
    }

    private class FakeMembershipClient(private val items: List<SnapshotItem>) : MembershipSnapshotClient {
        private fun receipt(replayed: Boolean): SnapshotReceipt {
            val revision = EligibilityIntegrity.sourceRevision(items)
            return SnapshotReceipt(
                SNAPSHOT_ID,
                revision,
                EligibilityIntegrity.sourceManifest(SNAPSHOT_ID, revision, items),
                items.size,
                CREATED_AT,
                replayed,
            )
        }

        override fun create(snapshotId: UUID): SnapshotReceipt = receipt(replayed = false)

        override fun receipt(snapshotId: UUID): SnapshotReceipt = receipt(replayed = true)

        override fun page(snapshotId: UUID, afterMemberId: UUID?, limit: Int): SnapshotPage {
            val remaining = items.filter { afterMemberId == null || it.memberId > afterMemberId }
            val page = remaining.take(limit)
            return SnapshotPage(snapshotId, page, if (remaining.size > limit) page.last().memberId else null)
        }
    }

    private class RecordingCirculationClient : CirculationBootstrapClient, CirculationParityClient {
        val requests = linkedMapOf<UUID, BootstrapRequest>()
        private val projections = ConcurrentHashMap<UUID, SnapshotItem>()
        var receiptReads = 0
        var bootstrapCalls = 0
        var corruptMemberId: UUID? = null

        fun seed(items: List<SnapshotItem>) {
            items.forEach { projections[it.memberId] = it }
        }

        override fun bootstrap(bootstrapId: UUID, request: BootstrapRequest): BootstrapReceipt {
            bootstrapCalls += 1
            requests[bootstrapId] = request
            seed(request.items)
            return result(bootstrapId, request, replayed = false)
        }

        override fun receipt(bootstrapId: UUID): BootstrapReceipt {
            receiptReads += 1
            return result(bootstrapId, requireNotNull(requests[bootstrapId]), replayed = true)
        }

        override fun eligibility(memberId: UUID): ProjectedEligibility {
            val item = requireNotNull(projections[memberId])
            return ProjectedEligibility(
                item.memberId,
                if (item.memberId == corruptMemberId) EligibilityStatus.SUSPENDED else item.status,
                if (item.memberId == corruptMemberId) "SECURITY_HOLD" else item.reasonCode,
                item.sourceVersion,
                item.sourceOccurredAt,
            )
        }

        private fun result(bootstrapId: UUID, request: BootstrapRequest, replayed: Boolean) = BootstrapReceipt(
            bootstrapId,
            request.sourceRevision,
            EligibilityIntegrity.bootstrapManifest(bootstrapId, request.sourceRevision, request.items),
            request.items.size,
            COMPLETED_AT,
            replayed,
        )
    }

    private companion object {
        val SNAPSHOT_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000008")
        val CREATED_AT: Instant = Instant.parse("2026-10-09T00:00:00Z")
        val OCCURRED_AT: Instant = Instant.parse("2026-10-08T00:00:00Z")
        val COMPLETED_AT: Instant = Instant.parse("2026-10-09T00:01:00Z")
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-09T00:02:00Z"), ZoneOffset.UTC)
        val TOKEN: String = "x".repeat(32)
    }
}
