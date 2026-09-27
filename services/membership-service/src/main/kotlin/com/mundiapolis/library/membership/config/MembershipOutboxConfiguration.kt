package com.mundiapolis.library.membership.config

import com.mundiapolis.library.membership.adapter.outbound.events.KafkaMembershipEventPublisher
import com.mundiapolis.library.membership.adapter.outbound.events.ProtobufMembershipEventEncoder
import com.mundiapolis.library.membership.service.MembershipBrokerPublisher
import com.mundiapolis.library.membership.service.MembershipEventEncoder
import com.mundiapolis.library.membership.service.MembershipOutboxDeliveryService
import com.mundiapolis.library.membership.service.MembershipOutboxStore
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.outbox", name = ["enabled"], havingValue = "true")
class MembershipOutboxConfiguration {
    @Bean
    fun membershipEventEncoder(
        objectMapper: ObjectMapper,
        properties: MembershipOutboxProperties,
    ): MembershipEventEncoder = ProtobufMembershipEventEncoder(
        objectMapper,
        properties.schemaSubject,
        properties.maximumEventBytes,
    )

    @Bean(destroyMethod = "close")
    fun membershipKafkaProducer(properties: MembershipOutboxProperties): Producer<String, ByteArray> {
        val kafka = properties.kafka
        val configuration = mutableMapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ProducerConfig.CLIENT_ID_CONFIG to properties.instanceId,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE,
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to 5,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to kafka.deliveryTimeout.toMillis().toInt(),
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to kafka.requestTimeout.toMillis().toInt(),
            ProducerConfig.MAX_BLOCK_MS_CONFIG to kafka.maximumBlock.toMillis(),
            ProducerConfig.MAX_REQUEST_SIZE_CONFIG to properties.maximumEventBytes + RECORD_OVERHEAD_BYTES,
            ProducerConfig.COMPRESSION_TYPE_CONFIG to "zstd",
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
        return KafkaProducer(configuration)
    }

    @Bean
    fun membershipBrokerPublisher(
        producer: Producer<String, ByteArray>,
        properties: MembershipOutboxProperties,
    ): MembershipBrokerPublisher = KafkaMembershipEventPublisher(producer, properties)

    @Bean
    fun membershipOutboxDeliveryService(
        store: MembershipOutboxStore,
        encoder: MembershipEventEncoder,
        publisher: MembershipBrokerPublisher,
        clock: Clock,
        properties: MembershipOutboxProperties,
    ) = MembershipOutboxDeliveryService(store, encoder, publisher, clock, properties)

    @Bean
    fun membershipOutboxWorker(
        service: MembershipOutboxDeliveryService,
        store: MembershipOutboxStore,
        clock: Clock,
        meterRegistry: MeterRegistry,
    ) = MembershipOutboxWorker(service, store, clock, meterRegistry)

    private fun putIfPresent(target: MutableMap<String, Any>, key: String, value: String?) {
        if (!value.isNullOrBlank()) target[key] = value
    }

    private companion object {
        const val RECORD_OVERHEAD_BYTES = 16 * 1024
    }
}

@Configuration(proxyBeanMethods = false)
class MembershipOutboxHealthConfiguration {
    @Bean(name = ["membershipOutbox"])
    fun membershipOutboxHealthIndicator(
        store: MembershipOutboxStore,
        clock: Clock,
        properties: MembershipOutboxProperties,
    ): HealthIndicator = MembershipOutboxHealthIndicator(store, clock, properties)
}

class MembershipOutboxWorker(
    private val service: MembershipOutboxDeliveryService,
    private val store: MembershipOutboxStore,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val pending = AtomicLong()
    private val leased = AtomicLong()
    private val blocked = AtomicLong()
    private val oldestPendingAgeSeconds = AtomicLong()
    private val deliveryCounter = meterRegistry.counter("mundia.membership.outbox.events.published")
    private val failureCounter = meterRegistry.counter("mundia.membership.outbox.worker.failures")
    private val cleanupCounter = meterRegistry.counter("mundia.membership.outbox.cleanup.deleted")

    init {
        meterRegistry.gauge("mundia.membership.outbox.pending", pending)
        meterRegistry.gauge("mundia.membership.outbox.leased", leased)
        meterRegistry.gauge("mundia.membership.outbox.blocked", blocked)
        meterRegistry.gauge("mundia.membership.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds)
    }

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval}")
    fun deliver() {
        try {
            val cycle = service.deliverBatch()
            deliveryCounter.increment(cycle.published.toDouble())
            refreshStatistics()
        } catch (_: Exception) {
            failureCounter.increment()
            logger.error("Membership outbox delivery cycle failed")
        }
    }

    @Scheduled(fixedDelayString = "\${app.outbox.cleanup-interval}")
    fun cleanup() {
        try {
            cleanupCounter.increment(service.cleanupPublished().toDouble())
        } catch (_: Exception) {
            failureCounter.increment()
            logger.error("Membership outbox cleanup failed")
        }
    }

    private fun refreshStatistics() {
        val now = clock.instant()
        val statistics = store.statistics(now)
        pending.set(statistics.pending)
        leased.set(statistics.leased)
        blocked.set(statistics.blocked)
        oldestPendingAgeSeconds.set(
            statistics.oldestPendingCreatedAt
                ?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
                ?: 0,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(MembershipOutboxWorker::class.java)
    }
}

class MembershipOutboxHealthIndicator(
    private val store: MembershipOutboxStore,
    private val clock: Clock,
    private val properties: MembershipOutboxProperties,
) : HealthIndicator {
    override fun health(): Health = if (!properties.enabled) {
        Health.up().withDetail("enabled", false).build()
    } else try {
        val now = clock.instant()
        val statistics = store.statistics(now)
        val oldestAge = statistics.oldestPendingCreatedAt?.let { Duration.between(it, now) }
        val unhealthy = statistics.blocked > 0 ||
            (oldestAge != null && oldestAge > properties.maximumPendingAge)
        (if (unhealthy) Health.down() else Health.up())
            .withDetail("pending", statistics.pending)
            .withDetail("leased", statistics.leased)
            .withDetail("blocked", statistics.blocked)
            .withDetail("oldestPendingAgeSeconds", oldestAge?.seconds ?: 0)
            .build()
    } catch (_: Exception) {
        Health.down().build()
    }
}
