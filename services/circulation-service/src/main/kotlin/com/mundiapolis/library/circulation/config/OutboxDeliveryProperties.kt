package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.outbox")
data class OutboxDeliveryProperties(
    val enabled: Boolean,
    val instanceId: String,
    val topic: String,
    val schemaSubject: String,
    val pollInterval: Duration,
    val leaseDuration: Duration,
    val batchSize: Int,
    val maximumAttempts: Int,
    val retryBaseDelay: Duration,
    val retryMaximumDelay: Duration,
    val publishedRetention: Duration,
    val cleanupInterval: Duration,
    val cleanupBatchSize: Int,
    val maximumEventBytes: Int,
    val maximumPendingAge: Duration,
    val kafka: KafkaProperties,
) {
    @get:AssertTrue(message = "enabled outbox delivery configuration is unsafe or inconsistent")
    val isSafeConfiguration: Boolean
        get() {
            if (!enabled) {
                return true
            }
            if (
                !INSTANCE_ID.matches(instanceId) ||
                !TOPIC.matches(topic) ||
                !SCHEMA_SUBJECT.matches(schemaSubject) ||
                pollInterval !in MIN_POLL_INTERVAL..MAX_POLL_INTERVAL ||
                batchSize !in 1..MAX_BATCH_SIZE ||
                maximumAttempts !in 1..MAXIMUM_DELIVERY_ATTEMPTS ||
                retryBaseDelay !in MIN_RETRY_DELAY..MAX_RETRY_DELAY ||
                retryMaximumDelay !in retryBaseDelay..MAX_RETRY_DELAY ||
                publishedRetention !in MIN_PUBLISHED_RETENTION..MAX_PUBLISHED_RETENTION ||
                cleanupInterval !in MIN_CLEANUP_INTERVAL..MAX_CLEANUP_INTERVAL ||
                cleanupBatchSize !in 1..MAX_CLEANUP_BATCH_SIZE ||
                maximumEventBytes !in MIN_EVENT_BYTES..MAX_EVENT_BYTES ||
                maximumPendingAge !in MINIMUM_PENDING_AGE..MAXIMUM_PENDING_AGE ||
                !kafka.isSafe()
            ) {
                return false
            }

            val worstCaseDelivery = runCatching {
                kafka.maximumBlock.plus(kafka.deliveryTimeout)
                    .multipliedBy(batchSize.toLong())
                    .plus(LEASE_SAFETY_MARGIN)
            }.getOrNull() ?: return false
            return leaseDuration in worstCaseDelivery..MAX_LEASE_DURATION
        }

    data class KafkaProperties(
        val bootstrapServers: List<String>,
        val securityProtocol: String,
        val allowInsecureTransport: Boolean,
        val saslMechanism: String?,
        val saslJaasConfig: String?,
        val truststoreLocation: String?,
        val truststorePassword: String?,
        val keystoreLocation: String?,
        val keystorePassword: String?,
        val keyPassword: String?,
        val deliveryTimeout: Duration,
        val requestTimeout: Duration,
        val maximumBlock: Duration,
    ) {
        fun isSafe(): Boolean {
            if (
                bootstrapServers.isEmpty() ||
                bootstrapServers.size > MAX_BOOTSTRAP_SERVERS ||
                bootstrapServers.any { !BOOTSTRAP_SERVER.matches(it) } ||
                deliveryTimeout !in MIN_KAFKA_TIMEOUT..MAX_KAFKA_TIMEOUT ||
                requestTimeout !in MIN_KAFKA_TIMEOUT..deliveryTimeout ||
                maximumBlock !in MIN_KAFKA_TIMEOUT..MAX_KAFKA_TIMEOUT
            ) {
                return false
            }
            return when (securityProtocol) {
                "PLAINTEXT" -> allowInsecureTransport
                "SSL" ->
                    !allowInsecureTransport &&
                        !keystoreLocation.isNullOrBlank() &&
                        !keystorePassword.isNullOrBlank() &&
                        !keyPassword.isNullOrBlank()

                "SASL_SSL" ->
                    !allowInsecureTransport &&
                        !saslMechanism.isNullOrBlank() &&
                        !saslJaasConfig.isNullOrBlank()

                else -> false
            }
        }
    }

    private companion object {
        val INSTANCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
        val TOPIC = Regex("[A-Za-z0-9._-]{1,249}")
        val SCHEMA_SUBJECT = Regex("[A-Za-z0-9._-]{1,249}")
        val BOOTSTRAP_SERVER =
            Regex("""(?:\[[0-9A-Fa-f:]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):[1-9][0-9]{0,4}""")
        val MIN_POLL_INTERVAL: Duration = Duration.ofMillis(100)
        val MAX_POLL_INTERVAL: Duration = Duration.ofSeconds(30)
        val MIN_RETRY_DELAY: Duration = Duration.ofMillis(100)
        val MAX_RETRY_DELAY: Duration = Duration.ofHours(1)
        val MIN_PUBLISHED_RETENTION: Duration = Duration.ofHours(1)
        val MAX_PUBLISHED_RETENTION: Duration = Duration.ofDays(365)
        val MIN_CLEANUP_INTERVAL: Duration = Duration.ofMinutes(1)
        val MAX_CLEANUP_INTERVAL: Duration = Duration.ofDays(1)
        val MIN_KAFKA_TIMEOUT: Duration = Duration.ofMillis(100)
        val MAX_KAFKA_TIMEOUT: Duration = Duration.ofMinutes(2)
        val MAX_LEASE_DURATION: Duration = Duration.ofMinutes(15)
        val MINIMUM_PENDING_AGE: Duration = Duration.ofSeconds(10)
        val MAXIMUM_PENDING_AGE: Duration = Duration.ofDays(1)
        val LEASE_SAFETY_MARGIN: Duration = Duration.ofSeconds(10)
        const val MAX_BATCH_SIZE = 100
        const val MAX_CLEANUP_BATCH_SIZE = 10_000
        const val MAXIMUM_DELIVERY_ATTEMPTS = 1_000
        const val MIN_EVENT_BYTES = 1_024
        const val MAX_EVENT_BYTES = 1_048_576
        const val MAX_BOOTSTRAP_SERVERS = 20
    }
}
