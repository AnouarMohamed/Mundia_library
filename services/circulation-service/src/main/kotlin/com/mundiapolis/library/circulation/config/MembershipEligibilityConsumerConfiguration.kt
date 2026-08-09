package com.mundiapolis.library.circulation.config

import com.mundiapolis.library.circulation.adapter.`in`.events.MembershipEligibilityKafkaConsumer
import com.mundiapolis.library.circulation.adapter.`in`.events.MembershipEligibilityRecordDecoder
import com.mundiapolis.library.circulation.application.port.inbound.ApplyMembershipEligibilityEventUseCase
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MembershipEligibilityConsumerConfiguration {
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(
        prefix = "app.membership-consumer",
        name = ["enabled"],
        havingValue = "true",
    )
    fun membershipEligibilityKafkaClient(
        properties: MembershipEligibilityConsumerProperties,
    ): Consumer<String, ByteArray> {
        val kafka = properties.kafka
        val configuration = mutableMapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.CLIENT_ID_CONFIG to properties.instanceId,
            ConsumerConfig.GROUP_ID_CONFIG to properties.groupId,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to properties.maximumPollRecords,
            ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG to
                properties.maximumEventBytes + RECORD_OVERHEAD_BYTES,
            ConsumerConfig.FETCH_MAX_BYTES_CONFIG to
                (properties.maximumEventBytes + RECORD_OVERHEAD_BYTES) *
                properties.maximumPollRecords,
            ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG to kafka.requestTimeout.toMillis().toInt(),
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to kafka.sessionTimeout.toMillis().toInt(),
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to
                kafka.heartbeatInterval.toMillis().toInt(),
            CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to kafka.securityProtocol,
            CommonClientConfigs.CLIENT_DNS_LOOKUP_CONFIG to "use_all_dns_ips",
        )
        putIfPresent(configuration, SaslConfigs.SASL_MECHANISM, kafka.saslMechanism)
        putIfPresent(configuration, SaslConfigs.SASL_JAAS_CONFIG, kafka.saslJaasConfig)
        putIfPresent(configuration, SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, kafka.truststoreLocation)
        putIfPresent(configuration, SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kafka.truststorePassword)
        putIfPresent(configuration, SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, kafka.keystoreLocation)
        putIfPresent(configuration, SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kafka.keystorePassword)
        putIfPresent(configuration, SslConfigs.SSL_KEY_PASSWORD_CONFIG, kafka.keyPassword)
        return KafkaConsumer(configuration)
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.membership-consumer",
        name = ["enabled"],
        havingValue = "true",
    )
    fun membershipEligibilityRecordDecoder(
        properties: MembershipEligibilityConsumerProperties,
    ): MembershipEligibilityRecordDecoder = MembershipEligibilityRecordDecoder(properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "app.membership-consumer",
        name = ["enabled"],
        havingValue = "true",
    )
    fun membershipEligibilityKafkaConsumer(
        consumer: Consumer<String, ByteArray>,
        decoder: MembershipEligibilityRecordDecoder,
        applyEligibilityEvent: ApplyMembershipEligibilityEventUseCase,
        timeProvider: TimeProvider,
        properties: MembershipEligibilityConsumerProperties,
        meterRegistry: MeterRegistry,
    ): MembershipEligibilityKafkaConsumer = MembershipEligibilityKafkaConsumer(
        consumer = consumer,
        decoder = decoder,
        applyEligibilityEvent = applyEligibilityEvent,
        timeProvider = timeProvider,
        properties = properties,
        meterRegistry = meterRegistry,
    )

    @Bean
    fun membershipEligibilityConsumerHealthIndicator(
        consumer: ObjectProvider<MembershipEligibilityKafkaConsumer>,
        properties: MembershipEligibilityConsumerProperties,
        timeProvider: TimeProvider,
    ): HealthIndicator = HealthIndicator {
        if (!properties.enabled) {
            return@HealthIndicator Health.up().withDetail("enabled", false).build()
        }
        val snapshot = consumer.ifAvailable?.healthSnapshot(timeProvider.now())
            ?: return@HealthIndicator Health.down().withDetail("enabled", true).build()
        val builder = when {
            snapshot.ready -> Health.up()
            snapshot.starting -> Health.outOfService()
            else -> Health.down()
        }
        builder
            .withDetail("enabled", true)
            .withDetail("assignments", snapshot.assignments)
            .withDetail("lastProcessedOffset", snapshot.lastProcessedOffset)
            .apply {
                snapshot.failure?.let { withDetail("failure", it.name) }
            }
            .build()
    }

    private fun putIfPresent(target: MutableMap<String, Any>, key: String, value: String?) {
        if (!value.isNullOrBlank()) target[key] = value
    }

    private companion object {
        const val RECORD_OVERHEAD_BYTES = 16 * 1024
    }
}
