package com.mundiapolis.library.catalog.config

import com.mundiapolis.library.catalog.adapter.outbound.events.KafkaCatalogEventPublisher
import com.mundiapolis.library.catalog.adapter.outbound.events.ProtobufCatalogEventEncoder
import com.mundiapolis.library.catalog.service.CatalogBrokerPublisher
import com.mundiapolis.library.catalog.service.CatalogEventEncoder
import com.mundiapolis.library.catalog.service.CatalogOutboxDeliveryService
import com.mundiapolis.library.catalog.service.CatalogOutboxStore
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
class CatalogOutboxConfiguration {
    @Bean
    fun catalogEventEncoder(
        objectMapper: ObjectMapper,
        properties: CatalogOutboxProperties,
    ): CatalogEventEncoder = ProtobufCatalogEventEncoder(objectMapper, properties)

    @Bean(destroyMethod = "close")
    fun catalogKafkaProducer(properties: CatalogOutboxProperties): Producer<String, ByteArray> {
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
    fun catalogBrokerPublisher(
        producer: Producer<String, ByteArray>,
        properties: CatalogOutboxProperties,
    ): CatalogBrokerPublisher = KafkaCatalogEventPublisher(producer, properties)

    @Bean
    fun catalogOutboxDeliveryService(
        store: CatalogOutboxStore,
        encoder: CatalogEventEncoder,
        publisher: CatalogBrokerPublisher,
        clock: Clock,
        properties: CatalogOutboxProperties,
    ) = CatalogOutboxDeliveryService(store, encoder, publisher, clock, properties)

    @Bean
    fun catalogOutboxWorker(
        service: CatalogOutboxDeliveryService,
        store: CatalogOutboxStore,
        clock: Clock,
        meterRegistry: MeterRegistry,
    ) = CatalogOutboxWorker(service, store, clock, meterRegistry)

    private fun putIfPresent(target: MutableMap<String, Any>, key: String, value: String?) {
        if (!value.isNullOrBlank()) target[key] = value
    }

    private companion object {
        const val RECORD_OVERHEAD_BYTES = 16 * 1024
    }
}

@Configuration(proxyBeanMethods = false)
class CatalogOutboxHealthConfiguration {
    @Bean(name = ["catalogOutbox"])
    fun catalogOutboxHealthIndicator(
        store: CatalogOutboxStore,
        clock: Clock,
        properties: CatalogOutboxProperties,
    ): HealthIndicator = CatalogOutboxHealthIndicator(store, clock, properties)
}

class CatalogOutboxWorker(
    private val service: CatalogOutboxDeliveryService,
    private val store: CatalogOutboxStore,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val pending = AtomicLong()
    private val leased = AtomicLong()
    private val blocked = AtomicLong()
    private val oldestPendingAgeSeconds = AtomicLong()
    private val deliveryCounter = meterRegistry.counter("mundia.catalog.outbox.events.published")
    private val failureCounter = meterRegistry.counter("mundia.catalog.outbox.worker.failures")
    private val cleanupCounter = meterRegistry.counter("mundia.catalog.outbox.cleanup.deleted")

    init {
        meterRegistry.gauge("mundia.catalog.outbox.pending", pending)
        meterRegistry.gauge("mundia.catalog.outbox.leased", leased)
        meterRegistry.gauge("mundia.catalog.outbox.blocked", blocked)
        meterRegistry.gauge("mundia.catalog.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds)
    }

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval}")
    fun deliver() {
        try {
            val cycle = service.deliverBatch()
            deliveryCounter.increment(cycle.published.toDouble())
            refreshStatistics()
        } catch (_: Exception) {
            failureCounter.increment()
            logger.error("Catalog outbox delivery cycle failed")
        }
    }

    @Scheduled(fixedDelayString = "\${app.outbox.cleanup-interval}")
    fun cleanup() {
        try {
            cleanupCounter.increment(service.cleanupPublished().toDouble())
        } catch (_: Exception) {
            failureCounter.increment()
            logger.error("Catalog outbox cleanup failed")
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
        val logger = LoggerFactory.getLogger(CatalogOutboxWorker::class.java)
    }
}

class CatalogOutboxHealthIndicator(
    private val store: CatalogOutboxStore,
    private val clock: Clock,
    private val properties: CatalogOutboxProperties,
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
