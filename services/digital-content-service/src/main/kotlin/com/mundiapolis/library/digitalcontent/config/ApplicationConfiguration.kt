package com.mundiapolis.library.digitalcontent.config

import com.mundiapolis.library.digitalcontent.service.DigitalAssetReader
import com.mundiapolis.library.digitalcontent.service.CloudFrontDownloadUrlSigner
import com.mundiapolis.library.digitalcontent.service.DisabledDownloadUrlSigner
import com.mundiapolis.library.digitalcontent.service.DigitalContentService
import com.mundiapolis.library.digitalcontent.service.DownloadUrlSigner
import com.mundiapolis.library.digitalcontent.service.DisabledQuarantineUploadAuthorizer
import com.mundiapolis.library.digitalcontent.service.IngestionService
import com.mundiapolis.library.digitalcontent.service.IngestionStore
import com.mundiapolis.library.digitalcontent.service.QuarantineUploadAuthorizer
import com.mundiapolis.library.digitalcontent.service.S3QuarantineUploadAuthorizer
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.sqs.SqsClient
import com.mundiapolis.library.digitalcontent.adapter.`in`.scan.AwsSqsMalwareScanQueue
import com.mundiapolis.library.digitalcontent.adapter.`in`.scan.GuardDutyScanDecoder
import com.mundiapolis.library.digitalcontent.adapter.`in`.scan.MalwareScanSqsConsumer
import com.mundiapolis.library.digitalcontent.service.MalwareScanService
import com.mundiapolis.library.digitalcontent.service.MalwareScanStore
import com.mundiapolis.library.digitalcontent.service.ExternalResourceService
import com.mundiapolis.library.digitalcontent.service.ExternalResourceStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import kotlin.time.toKotlinDuration

@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()

    @Bean
    fun downloadUrlSigner(properties: CloudFrontProperties): DownloadUrlSigner =
        if (properties.enabled) CloudFrontDownloadUrlSigner(properties) else DisabledDownloadUrlSigner()

    @Bean
    fun digitalContentService(
        reader: DigitalAssetReader,
        signer: DownloadUrlSigner,
        clock: Clock,
    ) = DigitalContentService(reader, signer, clock)

    @Bean
    fun externalResourceService(
        store: ExternalResourceStore,
        clock: Clock,
    ) = ExternalResourceService(store, clock)

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.ingestion", name = ["enabled"], havingValue = "true")
    fun quarantineS3Client(properties: IngestionProperties): S3Client = S3Client {
        region = properties.region
        applicationId = "mundia-digital-content-service"
        callTimeout = properties.callTimeout.toKotlinDuration()
        attemptTimeout = properties.attemptTimeout.toKotlinDuration()
        retryStrategy { maxAttempts = 3 }
    }

    @Bean
    fun quarantineUploadAuthorizer(
        properties: IngestionProperties,
        client: org.springframework.beans.factory.ObjectProvider<S3Client>,
    ): QuarantineUploadAuthorizer = if (properties.enabled) {
        S3QuarantineUploadAuthorizer(requireNotNull(client.ifAvailable), properties)
    } else {
        DisabledQuarantineUploadAuthorizer()
    }

    @Bean
    fun ingestionService(
        store: IngestionStore,
        authorizer: QuarantineUploadAuthorizer,
        clock: Clock,
        properties: IngestionProperties,
    ) = IngestionService(store, authorizer, clock, properties.sessionLifetime.seconds)

    @Bean
    fun malwareScanService(store: MalwareScanStore, clock: Clock) = MalwareScanService(store, clock)

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.malware-scan", name = ["enabled"], havingValue = "true")
    fun malwareScanSqsClient(ingestion: IngestionProperties): SqsClient = SqsClient {
        region = ingestion.region
        applicationId = "mundia-digital-content-service"
        callTimeout = ingestion.callTimeout.toKotlinDuration()
        attemptTimeout = ingestion.attemptTimeout.toKotlinDuration()
        retryStrategy { maxAttempts = 3 }
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.malware-scan", name = ["enabled"], havingValue = "true")
    fun malwareScanConsumer(
        malwareScanSqsClient: SqsClient,
        objectMapper: ObjectMapper,
        ingestion: IngestionProperties,
        scan: MalwareScanProperties,
        clock: Clock,
        service: MalwareScanService,
        registry: MeterRegistry,
    ): MalwareScanSqsConsumer {
        require(ingestion.enabled) { "malware scanning requires quarantine ingestion to be enabled" }
        require(
            scan.queueUrl.startsWith(
                "https://sqs.${ingestion.region}.amazonaws.com/${ingestion.expectedBucketOwner}/",
            ),
        ) { "malware scan queue must belong to the configured region and account" }
        return MalwareScanSqsConsumer(
            AwsSqsMalwareScanQueue(malwareScanSqsClient, scan),
            GuardDutyScanDecoder(objectMapper, ingestion, scan, clock),
            service,
            scan,
            clock,
            registry,
        )
    }

    @Bean
    fun malwareScanHealthIndicator(
        consumer: ObjectProvider<MalwareScanSqsConsumer>,
        properties: MalwareScanProperties,
        clock: Clock,
    ): HealthIndicator = HealthIndicator {
        if (!properties.enabled) return@HealthIndicator Health.up().withDetail("enabled", false).build()
        val snapshot = consumer.ifAvailable?.healthSnapshot(clock.instant())
            ?: return@HealthIndicator Health.down().withDetail("enabled", true).build()
        val health = when {
            snapshot.ready -> Health.up()
            snapshot.starting -> Health.outOfService()
            else -> Health.down()
        }
        health.withDetail("enabled", true)
            .withDetail("consecutiveFailures", snapshot.consecutiveFailures)
            .withDetail("lastSuccessfulPoll", snapshot.lastSuccessfulPoll?.toString() ?: "never")
            .build()
    }
}
