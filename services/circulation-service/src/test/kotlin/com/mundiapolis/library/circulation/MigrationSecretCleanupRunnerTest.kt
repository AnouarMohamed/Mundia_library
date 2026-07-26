package com.mundiapolis.library.circulation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MigrationSecretCleanupRunnerTest {
    @Test
    fun `startup modes are exact mutually exclusive and fail closed`() {
        assertThat(MigrationOnlyRunner.resolveStartupMode(emptyMap()))
            .isEqualTo(StartupMode.RUNTIME)
        assertThat(
            MigrationOnlyRunner.resolveStartupMode(mapOf("APP_MIGRATION_ONLY" to "true")),
        ).isEqualTo(StartupMode.MIGRATION_ONLY)
        assertThat(
            MigrationOnlyRunner.resolveStartupMode(
                mapOf("APP_MIGRATION_SECRET_CLEANUP_ONLY" to "true"),
            ),
        ).isEqualTo(StartupMode.MIGRATION_SECRET_CLEANUP_ONLY)
        assertThat(
            MigrationOnlyRunner.resolveStartupMode(
                mapOf(
                    "APP_MIGRATION_ONLY" to "true",
                    "APP_MIGRATION_SECRET_CLEANUP_ONLY" to "true",
                ),
            ),
        ).isEqualTo(StartupMode.INVALID)
        assertThat(
            MigrationOnlyRunner.resolveStartupMode(mapOf("APP_MIGRATION_ONLY" to "TRUE")),
        ).isEqualTo(StartupMode.INVALID)
    }

    @Test
    fun `cleanup refuses missing resource identity before making API requests`() {
        var requestCount = 0
        val errors = mutableListOf<String>()

        val exitCode =
            MigrationSecretCleanupRunner.execute(
                environment = emptyMap(),
                info = {},
                error = errors::add,
                deleteResource = {
                    requestCount += 1
                    200
                },
            )

        assertThat(exitCode).isEqualTo(MigrationOnlyRunner.CONFIGURATION_ERROR_EXIT_CODE)
        assertThat(requestCount).isZero()
        assertThat(errors).containsExactly(
            "Migration credential cleanup refused: required resource identity is invalid",
        )
    }

    @Test
    fun `cleanup deletes the external source before the target secret`() {
        val requests = mutableListOf<MigrationSecretCleanupRunner.CleanupRequest>()
        val attempts = mutableMapOf<String, Int>()

        val exitCode =
            MigrationSecretCleanupRunner.execute(
                environment =
                    mapOf(
                        "POD_NAMESPACE" to "mundia-prod",
                        "MIGRATION_EXTERNAL_SECRET_NAME" to
                            "circulation-mundia-service-migration",
                        "MIGRATION_SECRET_NAME" to "circulation-migration",
                    ),
                info = {},
                error = {},
                deleteResource = {
                    requests += it
                    val attempt = attempts.merge(it.path, 1, Int::plus) ?: 1
                    if (attempt < 3) 202 else 404
                },
                bearerToken = "unit-test-token",
                pause = {},
            )

        assertThat(exitCode).isZero()
        val sourcePath =
            "/apis/external-secrets.io/v1/namespaces/mundia-prod/" +
                "externalsecrets/circulation-mundia-service-migration"
        val targetPath =
            "/api/v1/namespaces/mundia-prod/secrets/circulation-migration"
        assertThat(requests.map { it.path }).containsExactly(
            sourcePath,
            sourcePath,
            sourcePath,
            targetPath,
            targetPath,
            targetPath,
        )
        assertThat(requests).allMatch { it.bearerToken == "unit-test-token" }
    }

    @Test
    fun `cleanup still attempts target deletion when source deletion fails`() {
        val requests = mutableListOf<MigrationSecretCleanupRunner.CleanupRequest>()
        val errors = mutableListOf<String>()

        val exitCode =
            MigrationSecretCleanupRunner.execute(
                environment =
                    mapOf(
                        "POD_NAMESPACE" to "mundia-prod",
                        "MIGRATION_EXTERNAL_SECRET_NAME" to
                            "circulation-mundia-service-migration",
                        "MIGRATION_SECRET_NAME" to "circulation-migration",
                    ),
                info = {},
                error = errors::add,
                deleteResource = {
                    requests += it
                    if (requests.size == 1) 403 else 404
                },
                bearerToken = "unit-test-token",
                pause = {},
            )

        assertThat(exitCode).isEqualTo(1)
        assertThat(requests).hasSize(2)
        assertThat(errors).containsExactly("Migration credential cleanup failed")
        assertThat(errors.joinToString()).doesNotContain("unit-test-token")
    }

    @Test
    fun `cleanup never treats a permanently pending deletion as success`() {
        val requests = mutableListOf<MigrationSecretCleanupRunner.CleanupRequest>()
        val errors = mutableListOf<String>()

        val exitCode =
            MigrationSecretCleanupRunner.execute(
                environment =
                    mapOf(
                        "POD_NAMESPACE" to "mundia-prod",
                        "MIGRATION_EXTERNAL_SECRET_NAME" to
                            "circulation-mundia-service-migration",
                        "MIGRATION_SECRET_NAME" to "circulation-migration",
                    ),
                info = {},
                error = errors::add,
                deleteResource = {
                    requests += it
                    if (it.path.contains("externalsecrets")) 202 else 404
                },
                bearerToken = "unit-test-token",
                maximumDeleteAttempts = 3,
                pause = {},
            )

        assertThat(exitCode).isEqualTo(1)
        assertThat(requests.count { it.path.contains("externalsecrets") })
            .isEqualTo(3)
        assertThat(requests.count { it.path.contains("/secrets/") })
            .isEqualTo(1)
        assertThat(errors).containsExactly("Migration credential cleanup failed")
    }
}
