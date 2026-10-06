package com.mundiapolis.library.bff.config

import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ServiceClientConfiguration {
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()

    @Bean
    fun catalogRestClient(
        observationRegistry: ObservationRegistry,
        catalog: CatalogClientProperties,
        bff: BffProperties,
    ): RestClient {
        require(catalog.isSafeFor(bff.deploymentTier)) {
            "Catalog service transport must use HTTPS outside local development"
        }
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(catalog.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(catalog.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(catalog.baseUrl.toASCIIString())
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    @Bean
    fun membershipRestClient(
        observationRegistry: ObservationRegistry,
        membership: MembershipClientProperties,
        bff: BffProperties,
    ): RestClient {
        require(membership.isSafeFor(bff.deploymentTier)) {
            "Membership service transport must use HTTPS outside local development"
        }
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(membership.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(membership.readTimeout)
        }

        return RestClient.builder()
            .baseUrl(membership.baseUrl.toASCIIString())
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    @Bean
    fun circulationRestClient(
        observationRegistry: ObservationRegistry,
        circulation: CirculationClientProperties,
        bff: BffProperties,
    ): RestClient {
        require(circulation.isSafeFor(bff.deploymentTier)) {
            "Circulation service transport must use HTTPS outside local development"
        }
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(circulation.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(circulation.readTimeout)
        }

        return RestClient.builder()
            .baseUrl(circulation.baseUrl.toASCIIString())
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    @Bean
    fun notificationRestClient(
        observationRegistry: ObservationRegistry,
        notification: NotificationClientProperties,
        bff: BffProperties,
    ): RestClient {
        require(notification.isSafeFor(bff.deploymentTier)) {
            "Notification service transport must use HTTPS outside local development"
        }
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(notification.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(notification.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(notification.baseUrl.toASCIIString())
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    @Bean
    fun digitalContentRestClient(
        observationRegistry: ObservationRegistry,
        digitalContent: DigitalContentClientProperties,
        bff: BffProperties,
    ): RestClient {
        require(digitalContent.isSafeFor(bff.deploymentTier)) {
            "Digital Content service transport must use HTTPS outside local development"
        }
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(digitalContent.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(digitalContent.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(digitalContent.baseUrl.toASCIIString())
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
}
