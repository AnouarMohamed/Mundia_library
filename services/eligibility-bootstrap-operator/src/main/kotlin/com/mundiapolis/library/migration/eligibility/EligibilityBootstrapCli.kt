package com.mundiapolis.library.migration.eligibility

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    try {
        val options = CliOptions.parse(arguments)
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()
        val transport = BoundedJsonTransport(mapper)
        val membership = HttpMembershipSnapshotClient(
            requiredEnvironment("MEMBERSHIP_SERVICE_URL"),
            requiredEnvironment("MEMBERSHIP_SNAPSHOT_BEARER_TOKEN"),
            options.allowLoopbackHttp,
            transport,
        )
        val circulationUrl = if (options.mode != OperatorMode.DRY_RUN) {
            requiredEnvironment("CIRCULATION_SERVICE_URL")
        } else {
            null
        }
        val circulationBootstrap = if (options.mode == OperatorMode.APPLY) {
            HttpCirculationBootstrapClient(
                requireNotNull(circulationUrl),
                requiredEnvironment("CIRCULATION_BOOTSTRAP_BEARER_TOKEN"),
                options.allowLoopbackHttp,
                transport,
            )
        } else {
            null
        }
        val circulationParity = if (options.mode != OperatorMode.DRY_RUN) {
            HttpCirculationParityClient(
                requireNotNull(circulationUrl),
                requiredEnvironment("CIRCULATION_PARITY_BEARER_TOKEN"),
                options.allowLoopbackHttp,
                transport,
            )
        } else {
            null
        }
        val evidence = EligibilityBootstrapOperator(
            membership,
            circulationBootstrap,
            circulationParity,
            Clock.systemUTC(),
        ).execute(
            OperatorCommand(options.snapshotId, options.batchSize, options.parityConcurrency, options.mode),
        )
        EvidenceWriter(mapper).write(options.evidenceFile, evidence)
        println(
            "Eligibility bootstrap ${evidence.mode.lowercase()} verified " +
                "${evidence.memberCount} members in ${evidence.batches.size} batches; evidence written.",
        )
    } catch (failure: Exception) {
        System.err.println("Eligibility bootstrap failed: ${failure.message ?: failure::class.simpleName}")
        exitProcess(1)
    }
}

data class CliOptions(
    val snapshotId: UUID,
    val evidenceFile: Path,
    val batchSize: Int,
    val parityConcurrency: Int,
    val mode: OperatorMode,
    val allowLoopbackHttp: Boolean,
) {
    companion object {
        fun parse(arguments: Array<String>): CliOptions {
            var snapshotId: UUID? = null
            var evidenceFile: Path? = null
            var batchSize = 100
            var parityConcurrency = 8
            var mode = OperatorMode.DRY_RUN
            var allowLoopbackHttp = false
            var index = 0
            while (index < arguments.size) {
                when (val argument = arguments[index]) {
                    "--snapshot-id" -> snapshotId = parseUuid(value(arguments, ++index, argument))
                    "--evidence-file" -> evidenceFile = Path.of(value(arguments, ++index, argument))
                    "--batch-size" -> batchSize = value(arguments, ++index, argument).toIntOrNull()
                        ?: throw OperatorValidationException("--batch-size must be an integer")
                    "--parity-concurrency" -> parityConcurrency = value(arguments, ++index, argument).toIntOrNull()
                        ?: throw OperatorValidationException("--parity-concurrency must be an integer")
                    "--apply" -> mode = selectMode(mode, OperatorMode.APPLY)
                    "--verify-parity" -> mode = selectMode(mode, OperatorMode.PARITY)
                    "--allow-loopback-http" -> allowLoopbackHttp = true
                    else -> throw OperatorValidationException("Unknown argument: $argument")
                }
                index += 1
            }
            return CliOptions(
                snapshotId ?: throw OperatorValidationException("--snapshot-id is required"),
                evidenceFile ?: throw OperatorValidationException("--evidence-file is required"),
                batchSize,
                parityConcurrency,
                mode,
                allowLoopbackHttp,
            )
        }

        private fun selectMode(current: OperatorMode, requested: OperatorMode): OperatorMode {
            if (current != OperatorMode.DRY_RUN) {
                throw OperatorValidationException("--apply and --verify-parity are mutually exclusive")
            }
            return requested
        }

        private fun value(arguments: Array<String>, index: Int, option: String): String =
            arguments.getOrNull(index)?.takeUnless { it.startsWith("--") }
                ?: throw OperatorValidationException("$option requires a value")

        private fun parseUuid(raw: String): UUID = runCatching { UUID.fromString(raw) }
            .getOrElse { throw OperatorValidationException("--snapshot-id must be a UUID") }
    }
}

private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
    ?: throw OperatorValidationException("$name is required")
