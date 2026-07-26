package com.mundiapolis.library.circulation

import org.flywaydb.core.Flyway

internal enum class StartupMode {
    RUNTIME,
    MIGRATION_ONLY,
    MIGRATION_SECRET_CLEANUP_ONLY,
    INVALID,
}

/**
 * Runs Flyway without starting Spring, an HTTP listener, security filters, or business beans.
 *
 * Migration credentials are intentionally read from dedicated variables and are never copied
 * from the runtime datasource contract. Error reporting must remain value-free because database
 * driver exception messages can contain connection details.
 */
internal object MigrationOnlyRunner {
    const val MODE_VARIABLE = "APP_MIGRATION_ONLY"
    const val CLEANUP_MODE_VARIABLE = "APP_MIGRATION_SECRET_CLEANUP_ONLY"
    const val CONFIGURATION_ERROR_EXIT_CODE = 78

    private const val FAILURE_EXIT_CODE = 1
    private const val SUCCESS_EXIT_CODE = 0
    private const val JDBC_URL = "DATABASE_MIGRATION_URL"
    private const val USERNAME = "DATABASE_MIGRATION_USERNAME"
    private const val PASSWORD = "DATABASE_MIGRATION_PASSWORD"
    private val requiredVariables = listOf(JDBC_URL, USERNAME, PASSWORD)
    private val jdbcCredentialParameter =
        Regex("""(?i)[?&](?:user|username|password)=""")
    private val jdbcUserInfo =
        Regex("""(?i)^jdbc:postgresql://[^/@]+@""")

    fun resolveStartupMode(environment: Map<String, String>): StartupMode {
        val migrationMode = parseMode(environment[MODE_VARIABLE])
        val cleanupMode = parseMode(environment[CLEANUP_MODE_VARIABLE])
        if (migrationMode == null || cleanupMode == null || migrationMode && cleanupMode) {
            return StartupMode.INVALID
        }
        return when {
            migrationMode -> StartupMode.MIGRATION_ONLY
            cleanupMode -> StartupMode.MIGRATION_SECRET_CLEANUP_ONLY
            else -> StartupMode.RUNTIME
        }
    }

    fun execute(
        environment: Map<String, String>,
        info: (String) -> Unit = ::println,
        error: (String) -> Unit = System.err::println,
    ): Int {
        val missingVariables =
            requiredVariables.filter { environment[it].isNullOrBlank() }
        if (missingVariables.isNotEmpty()) {
            error(
                "Circulation migration refused: required dedicated migration " +
                    "configuration is missing (${missingVariables.joinToString()})",
            )
            return CONFIGURATION_ERROR_EXIT_CODE
        }

        val jdbcUrl = requireNotNull(environment[JDBC_URL])
        if (!isSafePostgresJdbcUrl(jdbcUrl)) {
            error(
                "Circulation migration refused: DATABASE_MIGRATION_URL must be a " +
                    "credential-free PostgreSQL JDBC URL",
            )
            return CONFIGURATION_ERROR_EXIT_CODE
        }

        val username = requireNotNull(environment[USERNAME])
        val password = requireNotNull(environment[PASSWORD])

        return try {
            info("Circulation schema migration starting")
            val flyway =
                Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .validateMigrationNaming(true)
                    .validateOnMigrate(true)
                    .load()

            val result = flyway.migrate()
            flyway.validate()
            info(
                "Circulation schema migration completed; " +
                    "${result.migrationsExecuted} migration(s) applied",
            )
            SUCCESS_EXIT_CODE
        } catch (_: Exception) {
            // Never include the exception or connection properties in output.
            error("Circulation schema migration failed")
            FAILURE_EXIT_CODE
        }
    }

    private fun isSafePostgresJdbcUrl(value: String): Boolean =
        value.length <= MAX_JDBC_URL_LENGTH &&
            value.startsWith("jdbc:postgresql://") &&
            value.none(Char::isISOControl) &&
            !jdbcUserInfo.containsMatchIn(value) &&
            !jdbcCredentialParameter.containsMatchIn(value)

    private fun parseMode(value: String?): Boolean? =
        when (value) {
            null, "false" -> false
            "true" -> true
            else -> null
        }

    private const val MAX_JDBC_URL_LENGTH = 2_048
}
