package com.mundiapolis.library.migration.eligibility

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.UUID

class EvidenceWriterTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `evidence is owner only and cannot overwrite an existing result`() {
        val destination = temporaryDirectory.resolve("evidence.json")
        val writer = EvidenceWriter(
            JsonMapper.builder()
                .addModule(KotlinModule.Builder().build())
                .build(),
        )
        writer.write(destination, evidence())

        assertThat(Files.readString(destination)).contains("\"mode\" : \"DRY_RUN\"")
        val permissions = Files.getPosixFilePermissions(destination)
        assertThat(permissions).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        assertThatThrownBy { writer.write(destination, evidence()) }
            .isInstanceOf(OperatorValidationException::class.java)
            .hasMessage("Evidence file already exists")
    }

    private fun evidence() = OperatorEvidence(
        mode = "DRY_RUN",
        snapshotId = UUID.fromString("80000000-0000-0000-0000-000000000008"),
        sourceRevision = "a".repeat(64),
        sourceManifestSha256 = "b".repeat(64),
        memberCount = 1,
        batchSize = 100,
        batches = emptyList(),
        parity = null,
        generatedAt = Instant.parse("2026-10-09T00:00:00Z"),
    )
}
