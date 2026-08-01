package com.mundiapolis.library.circulation.config

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxDeliveryPropertiesTest {
    @Test
    fun `lease covers the complete sequential batch delivery window`() {
        assertThat(properties(leaseDuration = Duration.ofSeconds(21)).isSafeConfiguration)
            .isFalse()
        assertThat(properties(leaseDuration = Duration.ofSeconds(22)).isSafeConfiguration)
            .isTrue()
    }

    @Test
    fun `plaintext broker transport requires an explicit local-only opt in`() {
        val unsafeKafka = kafka().copy(allowInsecureTransport = false)

        assertThat(properties(kafka = unsafeKafka).isSafeConfiguration).isFalse()
    }

    private fun properties(
        leaseDuration: Duration = Duration.ofSeconds(30),
        kafka: OutboxDeliveryProperties.KafkaProperties = kafka(),
    ): OutboxDeliveryProperties =
        OutboxDeliveryProperties(
            enabled = true,
            instanceId = "test-instance",
            topic = "mundia.circulation.events.v1",
            schemaSubject = "mundia.circulation.v1.CirculationEvent",
            pollInterval = Duration.ofMillis(500),
            leaseDuration = leaseDuration,
            batchSize = 2,
            maximumAttempts = 20,
            retryBaseDelay = Duration.ofSeconds(1),
            retryMaximumDelay = Duration.ofMinutes(5),
            publishedRetention = Duration.ofDays(30),
            cleanupInterval = Duration.ofHours(1),
            cleanupBatchSize = 1_000,
            maximumEventBytes = 262_144,
            maximumPendingAge = Duration.ofMinutes(5),
            kafka = kafka,
        )

    private fun kafka(): OutboxDeliveryProperties.KafkaProperties =
        OutboxDeliveryProperties.KafkaProperties(
            bootstrapServers = listOf("broker:9092"),
            securityProtocol = "PLAINTEXT",
            allowInsecureTransport = true,
            saslMechanism = null,
            saslJaasConfig = null,
            truststoreLocation = null,
            truststorePassword = null,
            keystoreLocation = null,
            keystorePassword = null,
            keyPassword = null,
            deliveryTimeout = Duration.ofSeconds(5),
            requestTimeout = Duration.ofSeconds(3),
            maximumBlock = Duration.ofSeconds(1),
        )
}
