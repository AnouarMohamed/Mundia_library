package com.mundiapolis.library.catalog.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.circulation-consumer")
data class CirculationConsumerProperties(
    val enabled: Boolean,
    val instanceId: String,
    val groupId: String,
    val topic: String,
    val schemaSubject: String,
    val schemaVersion: Int,
    val pollTimeout: Duration,
    val commitTimeout: Duration,
    val retryBackoff: Duration,
    val startupGracePeriod: Duration,
    val maximumPollSilence: Duration,
    val maximumPollRecords: Int,
    val maximumEventBytes: Int,
    val kafka: KafkaProperties,
) {
    @get:AssertTrue(message = "enabled circulation consumer configuration is unsafe or inconsistent")
    val isSafeConfiguration: Boolean
        get() = !enabled || (
            NAME.matches(instanceId) && NAME.matches(groupId) && TOPIC.matches(topic) &&
                TOPIC.matches(schemaSubject) && schemaVersion == 1 &&
                pollTimeout in Duration.ofMillis(100)..Duration.ofSeconds(10) &&
                commitTimeout in Duration.ofMillis(100)..Duration.ofSeconds(30) &&
                retryBackoff in Duration.ofMillis(10)..Duration.ofSeconds(5) &&
                startupGracePeriod in Duration.ofSeconds(1)..Duration.ofMinutes(5) &&
                maximumPollSilence in pollTimeout.multipliedBy(2)..Duration.ofMinutes(2) &&
                maximumPollRecords in 1..100 && maximumEventBytes in 128..1_048_576 &&
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
                bootstrapServers.isEmpty() || bootstrapServers.size > 20 ||
                bootstrapServers.any { !BOOTSTRAP_SERVER.matches(it) } ||
                requestTimeout !in Duration.ofMillis(100)..Duration.ofMinutes(2) ||
                sessionTimeout !in Duration.ofSeconds(6)..Duration.ofMinutes(1) ||
                heartbeatInterval !in Duration.ofSeconds(1)..sessionTimeout.dividedBy(3)
            ) return false
            return when (securityProtocol) {
                "PLAINTEXT" -> allowInsecureTransport
                "SSL" -> !allowInsecureTransport && !keystoreLocation.isNullOrBlank() &&
                    !keystorePassword.isNullOrBlank() && !keyPassword.isNullOrBlank()
                "SASL_SSL" -> !allowInsecureTransport && !saslMechanism.isNullOrBlank() &&
                    !saslJaasConfig.isNullOrBlank()
                else -> false
            }
        }
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
        val TOPIC = Regex("[A-Za-z0-9._-]{1,249}")
        val BOOTSTRAP_SERVER =
            Regex("""(?:\[[0-9A-Fa-f:]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):[1-9][0-9]{0,4}""")
    }
}
