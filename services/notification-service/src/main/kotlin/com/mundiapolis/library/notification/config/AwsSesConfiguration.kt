package com.mundiapolis.library.notification.config

import aws.sdk.kotlin.services.sesv2.SesV2Client
import com.mundiapolis.library.notification.adapter.outbound.email.AwsSdkSesEmailGateway
import com.mundiapolis.library.notification.adapter.outbound.email.AwsSesEmailProvider
import com.mundiapolis.library.notification.service.EmailProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.toKotlinDuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.email-worker", name = ["enabled"], havingValue = "true")
class AwsSesConfiguration {
    @Bean(destroyMethod = "close")
    fun sesV2Client(properties: AwsSesProperties): SesV2Client = SesV2Client {
        region = properties.region
        applicationId = "mundia-notification-service"
        callTimeout = properties.callTimeout.toKotlinDuration()
        attemptTimeout = properties.attemptTimeout.toKotlinDuration()
        retryStrategy { maxAttempts = 1 }
    }

    @Bean
    fun emailProvider(client: SesV2Client, properties: AwsSesProperties): EmailProvider =
        AwsSesEmailProvider(AwsSdkSesEmailGateway(client), properties)
}
