package com.mundiapolis.library.circulation.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.membership-consumer")
data class MembershipEligibilityConsumerProperties(
    val enabled: Boolean,
    val instanceId: String,
    val groupId: String,
    val topic: String,
    val schemaSubject: String,
    val schemaVersion: Int,
    val pollTimeout: Duration,
    val commitTimeout: Duration,
    val startupGracePeriod: Duration,
    val maximumPollSilence: Duration,
    val maximumPollRecords: Int,
    val maximumEventBytes: Int,
    val kafka: KafkaProperties,
) {
    @get:AssertTrue(message = "enabled membership consumer configuration is unsafe or inconsistent")
    val isSafeConfiguration: Boolean
        get() = !enabled ||
            (
                INSTANCE_ID.matches(instanceId) &&
                    GROUP_ID.matches(groupId) &&
                    TOPIC.matches(topic) &&
                    SCHEMA_SUBJECT.matches(schemaSubject) &&
                    schemaVersion == SUPPORTED_SCHEMA_VERSION &&
                    pollTimeout in MIN_POLL_TIMEOUT..MAX_POLL_TIMEOUT &&
                    commitTimeout in MIN_COMMIT_TIMEOUT..MAX_COMMIT_TIMEOUT &&
                    startupGracePeriod in MIN_STARTUP_GRACE..MAX_STARTUP_GRACE &&
                    maximumPollSilence in pollTimeout.multipliedBy(2)..MAX_POLL_SILENCE &&
                    maximumPollRecords in 1..MAX_POLL_RECORDS &&
                    maximumEventBytes in MIN_EVENT_BYTES..MAX_EVENT_BYTES &&
                    kafka.isSafe()
            )

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
        val requestTimeout: Duration,
        val sessionTimeout: Duration,
        val heartbeatInterval: Duration,
    ) {
        fun isSafe(): Boolean {
            if (
                bootstrapServers.isEmpty() ||
                bootstrapServers.size > MAX_BOOTSTRAP_SERVERS ||
                bootstrapServers.any { !BOOTSTRAP_SERVER.matches(it) } ||
                requestTimeout !in MIN_KAFKA_TIMEOUT..MAX_KAFKA_TIMEOUT ||
                sessionTimeout !in MIN_SESSION_TIMEOUT..MAX_SESSION_TIMEOUT ||
                heartbeatInterval !in MIN_HEARTBEAT_INTERVAL..sessionTimeout.dividedBy(3)
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

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private val INSTANCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
        private val GROUP_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
        private val TOPIC = Regex("[A-Za-z0-9._-]{1,249}")
        private val SCHEMA_SUBJECT = Regex("[A-Za-z0-9._-]{1,249}")
        private val BOOTSTRAP_SERVER =
            Regex("""(?:\[[0-9A-Fa-f:]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):[1-9][0-9]{0,4}""")
        private val MIN_POLL_TIMEOUT: Duration = Duration.ofMillis(100)
        private val MAX_POLL_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val MIN_COMMIT_TIMEOUT: Duration = Duration.ofMillis(100)
        private val MAX_COMMIT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val MIN_STARTUP_GRACE: Duration = Duration.ofSeconds(1)
        private val MAX_STARTUP_GRACE: Duration = Duration.ofMinutes(5)
        private val MAX_POLL_SILENCE: Duration = Duration.ofMinutes(2)
        private val MIN_KAFKA_TIMEOUT: Duration = Duration.ofMillis(100)
        private val MAX_KAFKA_TIMEOUT: Duration = Duration.ofMinutes(2)
        private val MIN_SESSION_TIMEOUT: Duration = Duration.ofSeconds(6)
        private val MAX_SESSION_TIMEOUT: Duration = Duration.ofMinutes(1)
        private val MIN_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(1)
        private const val MAX_POLL_RECORDS = 100
        private const val MIN_EVENT_BYTES = 128
        private const val MAX_EVENT_BYTES = 1_048_576
        private const val MAX_BOOTSTRAP_SERVERS = 20
    }
}
