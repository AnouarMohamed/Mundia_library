package com.mundiapolis.library.circulation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.TimeUnit

@Testcontainers
class MigrationOnlyApplicationIntegrationTest {
    @Test
    @Timeout(90)
    fun `missing dedicated credentials fails closed without starting Spring`() {
        val result =
            runApplicationJar(
                mapOf(
                    "APP_MIGRATION_ONLY" to "true",
                    "DATABASE_URL" to "jdbc:postgresql://runtime.invalid/runtime",
                    "DATABASE_USERNAME" to "runtime-user-must-not-be-used",
                    "DATABASE_PASSWORD" to "runtime-password-must-not-be-used",
                ),
            )

        assertThat(result.exitCode).isEqualTo(MigrationOnlyRunner.CONFIGURATION_ERROR_EXIT_CODE)
        assertThat(result.output)
            .contains("required dedicated migration configuration is missing")
            .doesNotContain("runtime.invalid")
            .doesNotContain("runtime-user-must-not-be-used")
            .doesNotContain("runtime-password-must-not-be-used")
            .doesNotContain("Started CirculationServiceApplication")
            .doesNotContain("Tomcat")
    }

    @Test
    @Timeout(120)
    fun `migration mode applies all migrations to PostgreSQL 18 and exits without HTTP startup`() {
        val migrationUsername = postgres.username
        val migrationPassword = postgres.password
        val environment =
            mapOf(
                "APP_MIGRATION_ONLY" to "true",
                "DATABASE_MIGRATION_URL" to postgres.jdbcUrl,
                "DATABASE_MIGRATION_USERNAME" to migrationUsername,
                "DATABASE_MIGRATION_PASSWORD" to migrationPassword,
            )

        val firstRun = runApplicationJar(environment)
        val secondRun = runApplicationJar(environment)

        assertThat(firstRun.exitCode).isZero()
        assertThat(secondRun.exitCode).isZero()
        assertThat(firstRun.output)
            .contains("Circulation schema migration completed")
            .doesNotContain(migrationUsername)
            .doesNotContain(migrationPassword)
            .doesNotContain("Started CirculationServiceApplication")
            .doesNotContain("Tomcat")
        assertThat(secondRun.output)
            .contains("0 migration(s) applied")
            .doesNotContain(migrationUsername)
            .doesNotContain(migrationPassword)

        DriverManager.getConnection(
            postgres.jdbcUrl,
            migrationUsername,
            migrationPassword,
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success
                    ORDER BY installed_rank
                    """.trimIndent(),
                ).use { rows ->
                    val versions = buildList {
                        while (rows.next()) {
                            add(rows.getString("version"))
                        }
                    }
                    assertThat(versions).containsExactly(
                        "1",
                        "2",
                        "3",
                        "4",
                        "5",
                        "6",
                        "7",
                        "8",
                        "9",
                        "10",
                        "11",
                        "12",
                    )
                }
            }
        }
    }

    @Test
    @Timeout(90)
    fun `credential-bearing JDBC URL is rejected without echoing its value`() {
        val passwordInUrl = "must-never-appear-in-output"
        val result =
            runApplicationJar(
                mapOf(
                    "APP_MIGRATION_ONLY" to "true",
                    "DATABASE_MIGRATION_URL" to
                        "jdbc:postgresql://db.invalid/library?password=$passwordInUrl",
                    "DATABASE_MIGRATION_USERNAME" to "migration-owner",
                    "DATABASE_MIGRATION_PASSWORD" to "separate-secret",
                ),
            )

        assertThat(result.exitCode).isEqualTo(MigrationOnlyRunner.CONFIGURATION_ERROR_EXIT_CODE)
        assertThat(result.output)
            .contains("credential-free PostgreSQL JDBC URL")
            .doesNotContain(passwordInUrl)
            .doesNotContain("db.invalid")
    }

    private fun runApplicationJar(overrides: Map<String, String>): ProcessResult {
        val jar = Path.of("build", "libs", "circulation-service.jar").toAbsolutePath()
        assertThat(jar).isRegularFile()

        val outputFile = Files.createTempFile("circulation-migration-test-", ".log")
        try {
            val processBuilder =
                ProcessBuilder(javaExecutable(), "-jar", jar.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
            val environment = processBuilder.environment()
            environment.keys
                .filter {
                    it.startsWith("APP_MIGRATION_") ||
                        it.startsWith("DATABASE_MIGRATION_") ||
                        it in LEGACY_DATABASE_VARIABLES
                }.forEach(environment::remove)
            environment.putAll(overrides)

            val process = processBuilder.start()
            val exited = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            val output = Files.readString(outputFile)
            assertThat(exited)
                .withFailMessage("migration process timed out; output:%n%s", output)
                .isTrue()
            return ProcessResult(process.exitValue(), output)
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    companion object {
        private val PROCESS_TIMEOUT = Duration.ofSeconds(60)
        private val LEGACY_DATABASE_VARIABLES =
            setOf("DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD")

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:18-alpine")
                .withDatabaseName("circulation_migration")
                .withUsername("circulation_migration_owner")
                .withPassword("migration-test-password-never-log")
    }
}
