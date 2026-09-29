package com.mundiapolis.library.notification.config

import com.mundiapolis.library.notification.adapter.`in`.worker.EmailDeliveryWorker
import com.mundiapolis.library.notification.service.EmailDeliveryService
import com.mundiapolis.library.notification.service.EmailDeliveryStore
import com.mundiapolis.library.notification.service.EmailProvider
import com.mundiapolis.library.notification.service.NotificationRecipientResolver
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class EmailDeliveryConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.email-worker", name = ["enabled"], havingValue = "true")
    fun emailDeliveryExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    @ConditionalOnProperty(prefix = "app.email-worker", name = ["enabled"], havingValue = "true")
    fun emailDeliveryService(
        store: EmailDeliveryStore,
        recipientResolver: NotificationRecipientResolver,
        provider: EmailProvider,
        emailDeliveryExecutor: ExecutorService,
        clock: Clock,
        properties: EmailDeliveryProperties,
    ): EmailDeliveryService = EmailDeliveryService(
        store,
        recipientResolver,
        provider,
        emailDeliveryExecutor,
        clock,
        properties,
    )

    @Bean
    @ConditionalOnProperty(prefix = "app.email-worker", name = ["enabled"], havingValue = "true")
    fun emailDeliveryWorker(
        service: EmailDeliveryService,
        store: EmailDeliveryStore,
        properties: EmailDeliveryProperties,
        meterRegistry: MeterRegistry,
        clock: Clock,
    ): EmailDeliveryWorker = EmailDeliveryWorker(service, store, properties, meterRegistry, clock)

    @Bean
    fun emailDeliveryHealthIndicator(
        worker: ObjectProvider<EmailDeliveryWorker>,
        properties: EmailDeliveryProperties,
    ): HealthIndicator = HealthIndicator {
        if (!properties.enabled) return@HealthIndicator Health.up().withDetail("enabled", false).build()
        val snapshot = worker.ifAvailable?.healthSnapshot()
            ?: return@HealthIndicator Health.down().withDetail("enabled", true).build()
        Health.up().withDetail("enabled", true)
            .withDetail("backlogWithinObjective", snapshot.backlogWithinObjective)
            .withDetail("pending", snapshot.pending)
            .withDetail("retrying", snapshot.retrying)
            .withDetail("deadLettered", snapshot.deadLettered)
            .withDetail("expiredLeases", snapshot.expiredLeases)
            .withDetail("oldestPendingAgeSeconds", snapshot.oldestPendingAgeSeconds)
            .build()
    }
}
