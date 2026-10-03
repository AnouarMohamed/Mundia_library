package com.mundiapolis.library.notification.config

import aws.sdk.kotlin.services.sqs.SqsClient
import com.mundiapolis.library.notification.adapter.`in`.feedback.AwsSqsSesFeedbackQueue
import com.mundiapolis.library.notification.adapter.`in`.feedback.HttpsSnsSigningKeyProvider
import com.mundiapolis.library.notification.adapter.`in`.feedback.SesFeedbackDecoder
import com.mundiapolis.library.notification.adapter.`in`.feedback.SesFeedbackSqsConsumer
import com.mundiapolis.library.notification.adapter.`in`.feedback.SnsSignatureVerifier
import com.mundiapolis.library.notification.service.SesFeedbackService
import com.mundiapolis.library.notification.service.SesFeedbackStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Clock
import kotlin.time.toKotlinDuration

@Configuration(proxyBeanMethods = false)
class SesFeedbackConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.ses-feedback", name = ["enabled"], havingValue = "true")
    fun sesFeedbackSqsClient(properties: SesFeedbackProperties): SqsClient = SqsClient {
        region = properties.region
        applicationId = "mundia-notification-service"
        callTimeout = properties.callTimeout.toKotlinDuration()
        attemptTimeout = properties.attemptTimeout.toKotlinDuration()
        retryStrategy { maxAttempts = 3 }
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ses-feedback", name = ["enabled"], havingValue = "true")
    fun snsCertificateHttpClient(properties: SesFeedbackProperties): HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.certificateConnectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Bean
    @ConditionalOnProperty(prefix = "app.ses-feedback", name = ["enabled"], havingValue = "true")
    fun sesFeedbackDecoder(
        objectMapper: ObjectMapper,
        snsCertificateHttpClient: HttpClient,
        properties: SesFeedbackProperties,
        clock: Clock,
    ): SesFeedbackDecoder {
        val keyProvider = HttpsSnsSigningKeyProvider(snsCertificateHttpClient, properties, clock)
        return SesFeedbackDecoder(objectMapper, SnsSignatureVerifier(keyProvider, properties, clock), properties)
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ses-feedback", name = ["enabled"], havingValue = "true")
    fun sesFeedbackService(store: SesFeedbackStore, clock: Clock): SesFeedbackService = SesFeedbackService(store, clock)

    @Bean
    @ConditionalOnProperty(prefix = "app.ses-feedback", name = ["enabled"], havingValue = "true")
    fun sesFeedbackConsumer(
        sesFeedbackSqsClient: SqsClient,
        decoder: SesFeedbackDecoder,
        service: SesFeedbackService,
        properties: SesFeedbackProperties,
        clock: Clock,
        meterRegistry: MeterRegistry,
    ): SesFeedbackSqsConsumer = SesFeedbackSqsConsumer(
        AwsSqsSesFeedbackQueue(sesFeedbackSqsClient, properties),
        decoder,
        service,
        properties,
        clock,
        meterRegistry,
    )

    @Bean
    fun sesFeedbackHealthIndicator(
        consumer: ObjectProvider<SesFeedbackSqsConsumer>,
        properties: SesFeedbackProperties,
        clock: Clock,
    ): HealthIndicator = HealthIndicator {
        if (!properties.enabled) return@HealthIndicator Health.up().withDetail("enabled", false).build()
        val snapshot = consumer.ifAvailable?.healthSnapshot(clock.instant())
            ?: return@HealthIndicator Health.down().withDetail("enabled", true).build()
        val builder = when {
            snapshot.ready -> Health.up()
            snapshot.starting -> Health.outOfService()
            else -> Health.down()
        }
        builder.withDetail("enabled", true)
            .withDetail("consecutiveFailures", snapshot.consecutiveFailures)
            .withDetail("lastSuccessfulPoll", snapshot.lastSuccessfulPoll?.toString() ?: "never")
            .build()
    }
}
