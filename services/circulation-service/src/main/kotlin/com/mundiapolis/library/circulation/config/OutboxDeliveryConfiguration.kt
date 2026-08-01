package com.mundiapolis.library.circulation.config

import com.mundiapolis.library.circulation.adapter.outbound.events.KafkaBrokerEventPublisher
import com.mundiapolis.library.circulation.adapter.outbound.events.ProtobufOutboxEventEncoder
import com.mundiapolis.library.circulation.application.port.outbound.BrokerEventPublisher
import com.mundiapolis.library.circulation.application.port.outbound.EventContractEncoder
import com.mundiapolis.library.circulation.application.port.outbound.OutboxDeliveryStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.service.OutboxDeliveryService
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong
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

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.outbox", name = ["enabled"], havingValue = "true")
class OutboxDeliveryConfiguration {
    @Bean
    fun eventContractEncoder(
        objectMapper: ObjectMapper,
        properties: OutboxDeliveryProperties,
    ): EventContractEncoder = ProtobufOutboxEventEncoder(objectMapper, properties)

    @Bean(destroyMethod = "close")
    fun outboxKafkaProducer(
        properties: OutboxDeliveryProperties,
    ): Producer<String, ByteArray> {
        val kafka = properties.kafka
        val configuration =
            mutableMapOf<String, Any>(
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
    fun brokerEventPublisher(
        producer: Producer<String, ByteArray>,
        properties: OutboxDeliveryProperties,
    ): BrokerEventPublisher = KafkaBrokerEventPublisher(producer, properties)

    @Bean
    fun outboxDeliveryService(
        store: OutboxDeliveryStore,
        encoder: EventContractEncoder,
        publisher: BrokerEventPublisher,
        timeProvider: TimeProvider,
        properties: OutboxDeliveryProperties,
    ): OutboxDeliveryService =
        OutboxDeliveryService(store, encoder, publisher, timeProvider, properties)

    @Bean
    fun outboxDeliveryWorker(
        service: OutboxDeliveryService,
        store: OutboxDeliveryStore,
        timeProvider: TimeProvider,
        meterRegistry: MeterRegistry,
    ): OutboxDeliveryWorker =
        OutboxDeliveryWorker(service, store, timeProvider, meterRegistry)

    @Bean
    fun outboxHealthIndicator(
        store: OutboxDeliveryStore,
        timeProvider: TimeProvider,
        properties: OutboxDeliveryProperties,
    ): HealthIndicator = OutboxHealthIndicator(store, timeProvider, properties)

    private fun putIfPresent(
        target: MutableMap<String, Any>,
        key: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank()) target[key] = value
    }

    private companion object {
        const val RECORD_OVERHEAD_BYTES = 16 * 1024
    }
}

class OutboxDeliveryWorker(
    private val service: OutboxDeliveryService,
    private val store: OutboxDeliveryStore,
    private val timeProvider: TimeProvider,
    meterRegistry: MeterRegistry,
) {
    private val pending = AtomicLong()
    private val leased = AtomicLong()
    private val blocked = AtomicLong()
    private val oldestPendingAgeSeconds = AtomicLong()
    private val deliveryCounter = meterRegistry.counter("mundia.outbox.events.published")
    private val failureCounter = meterRegistry.counter("mundia.outbox.delivery.worker.failures")
    private val cleanupCounter = meterRegistry.counter("mundia.outbox.cleanup.deleted")

    init {
        meterRegistry.gauge("mundia.outbox.pending", pending)
        meterRegistry.gauge("mundia.outbox.leased", leased)
        meterRegistry.gauge("mundia.outbox.blocked", blocked)
        meterRegistry.gauge("mundia.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds)
    }

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval}")
    fun deliver() {
        try {
            val result = service.deliverBatch()
            deliveryCounter.increment(result.published.toDouble())
            refreshStatistics()
        } catch (_: Exception) {
            failureCounter.increment()
            logger.error("Outbox delivery cycle failed")
        }
    }

    @Scheduled(fixedDelayString = "\${app.outbox.cleanup-interval}")
    fun cleanup() {
        try {
            cleanupCounter.increment(service.cleanupPublished().toDouble())
        } catch (_: Exception) {
            failureCounter.increment()
            logger.error("Outbox retention cleanup failed")
        }
    }

    private fun refreshStatistics() {
        val now = timeProvider.now()
        val statistics = store.statistics(now)
        pending.set(statistics.pending)
        leased.set(statistics.leased)
        blocked.set(statistics.blocked)
        oldestPendingAgeSeconds.set(
            statistics.oldestPendingCreatedAt
                ?.let { java.time.Duration.between(it, now).seconds.coerceAtLeast(0) }
                ?: 0,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OutboxDeliveryWorker::class.java)
    }
}

class OutboxHealthIndicator(
    private val store: OutboxDeliveryStore,
    private val timeProvider: TimeProvider,
    private val properties: OutboxDeliveryProperties,
) : HealthIndicator {
    override fun health(): Health =
        try {
            val now = timeProvider.now()
            val statistics = store.statistics(now)
            val oldestAge =
                statistics.oldestPendingCreatedAt?.let { java.time.Duration.between(it, now) }
            val unhealthy =
                statistics.blocked > 0 ||
                    (oldestAge != null && oldestAge > properties.maximumPendingAge)
            val builder = if (unhealthy) Health.down() else Health.up()
            builder
                .withDetail("pending", statistics.pending)
                .withDetail("leased", statistics.leased)
                .withDetail("blocked", statistics.blocked)
                .withDetail("oldestPendingAgeSeconds", oldestAge?.seconds ?: 0)
                .build()
        } catch (_: Exception) {
            Health.down().build()
        }
}
