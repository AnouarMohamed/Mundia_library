package com.mundiapolis.library.circulation

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Removes the short-lived migration credential material after a successful migration hook.
 *
 * This path starts no Spring context. Its separately bound ServiceAccount may only delete the
 * named ExternalSecret and target Secret; it cannot read either secret.
 */
internal object MigrationSecretCleanupRunner {
    private const val NAMESPACE = "POD_NAMESPACE"
    private const val EXTERNAL_SECRET_NAME = "MIGRATION_EXTERNAL_SECRET_NAME"
    private const val SECRET_NAME = "MIGRATION_SECRET_NAME"
    private const val FAILURE_EXIT_CODE = 1
    private const val SUCCESS_EXIT_CODE = 0
    private const val API_SERVER = "https://kubernetes.default.svc"
    private val tokenPath = Path.of("/var/run/secrets/migration-cleanup/token")
    private val caPath = Path.of("/var/run/secrets/migration-cleanup/ca.crt")
    private val dnsLabel = Regex("""^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$""")

    fun execute(
        environment: Map<String, String>,
        info: (String) -> Unit = ::println,
        error: (String) -> Unit = System.err::println,
        deleteResource: ((CleanupRequest) -> Int)? = null,
        bearerToken: String? = null,
        maximumDeleteAttempts: Int = DEFAULT_MAXIMUM_DELETE_ATTEMPTS,
        pause: (Duration) -> Unit = { duration -> Thread.sleep(duration.toMillis()) },
    ): Int {
        val namespace = environment[NAMESPACE]
        val externalSecretName = environment[EXTERNAL_SECRET_NAME]
        val secretName = environment[SECRET_NAME]
        if (
            !isResourceName(namespace) ||
            !isResourceName(externalSecretName) ||
            !isResourceName(secretName)
        ) {
            error("Migration credential cleanup refused: required resource identity is invalid")
            return MigrationOnlyRunner.CONFIGURATION_ERROR_EXIT_CODE
        }

        return try {
            val token = bearerToken ?: Files.readString(tokenPath).trim()
            require(token.isNotEmpty())
            val delete = deleteResource ?: kubernetesDeleter()
            val requests =
                listOf(
                    CleanupRequest(
                        "/apis/external-secrets.io/v1/namespaces/$namespace/" +
                            "externalsecrets/$externalSecretName",
                        token,
                    ),
                    CleanupRequest(
                        "/api/v1/namespaces/$namespace/secrets/$secretName",
                        token,
                    ),
                )

            info("Migration credential cleanup starting")
            // A Kubernetes DELETE may return 200/202 before finalizers and
            // controller reconciliation have finished. Repeating the
            // idempotent DELETE until 404 proves the source is absent before
            // the materialized Secret is removed, without granting permission
            // to read Secret data.
            val sourceAbsent =
                deleteUntilAbsent(
                    request = requests[0],
                    deleteResource = delete,
                    maximumAttempts = maximumDeleteAttempts,
                    pause = pause,
                )
            val targetAbsent =
                deleteUntilAbsent(
                    request = requests[1],
                    deleteResource = delete,
                    maximumAttempts = maximumDeleteAttempts,
                    pause = pause,
                )
            val cleanupFailed = !sourceAbsent || !targetAbsent
            if (cleanupFailed) {
                error("Migration credential cleanup failed")
                FAILURE_EXIT_CODE
            } else {
                info("Migration credential cleanup completed")
                SUCCESS_EXIT_CODE
            }
        } catch (_: Exception) {
            // Never include API responses, bearer tokens, or filesystem content in output.
            error("Migration credential cleanup failed")
            FAILURE_EXIT_CODE
        }
    }

    private fun kubernetesDeleter(): (CleanupRequest) -> Int {
        val client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(clusterSslContext())
                .build()
        return { request ->
            val httpRequest =
                HttpRequest.newBuilder(URI.create(API_SERVER + request.path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer ${request.bearerToken}")
                    .header("Accept", "application/json")
                    .DELETE()
                    .build()
            client.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
    }

    private fun deleteUntilAbsent(
        request: CleanupRequest,
        deleteResource: (CleanupRequest) -> Int,
        maximumAttempts: Int,
        pause: (Duration) -> Unit,
    ): Boolean {
        if (maximumAttempts !in 1..MAXIMUM_ALLOWED_DELETE_ATTEMPTS) return false

        repeat(maximumAttempts) { attempt ->
            val status =
                try {
                    deleteResource(request)
                } catch (_: Exception) {
                    RETRYABLE_STATUS
                }
            when {
                status == 404 -> return true
                status in RETRYABLE_DELETE_STATUSES || status >= 500 -> {
                    if (attempt + 1 < maximumAttempts) {
                        try {
                            pause(DELETE_RETRY_DELAY)
                        } catch (_: Exception) {
                            return false
                        }
                    }
                }
                else -> return false
            }
        }
        return false
    }

    private fun clusterSslContext(): SSLContext {
        val certificate =
            Files.newInputStream(caPath).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        keyStore.setCertificateEntry("kubernetes-cluster-ca", certificate)
        val trustManagers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagers.init(keyStore)
        return SSLContext.getInstance("TLS").apply {
            init(null, trustManagers.trustManagers, null)
        }
    }

    private fun isResourceName(value: String?): Boolean =
        value != null && value.length <= 63 && dnsLabel.matches(value)

    internal data class CleanupRequest(
        val path: String,
        val bearerToken: String,
    )

    private const val DEFAULT_MAXIMUM_DELETE_ATTEMPTS = 60
    private const val MAXIMUM_ALLOWED_DELETE_ATTEMPTS = 120
    private const val RETRYABLE_STATUS = 503
    private val DELETE_RETRY_DELAY = Duration.ofMillis(500)
    private val RETRYABLE_DELETE_STATUSES = setOf(200, 202, 408, 409, 425, 429)
}
