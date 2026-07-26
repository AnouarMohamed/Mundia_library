package com.mundiapolis.library.circulation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
class CirculationServiceApplication

fun main(args: Array<String>) {
    val environment = System.getenv()
    when (MigrationOnlyRunner.resolveStartupMode(environment)) {
        StartupMode.RUNTIME -> runApplication<CirculationServiceApplication>(*args)
        StartupMode.MIGRATION_ONLY -> exitProcess(MigrationOnlyRunner.execute(environment))
        StartupMode.MIGRATION_SECRET_CLEANUP_ONLY ->
            exitProcess(MigrationSecretCleanupRunner.execute(environment))
        StartupMode.INVALID -> {
            System.err.println(
                "Circulation startup refused: maintenance modes must be exact and exclusive",
            )
            exitProcess(MigrationOnlyRunner.CONFIGURATION_ERROR_EXIT_CODE)
        }
    }
}
