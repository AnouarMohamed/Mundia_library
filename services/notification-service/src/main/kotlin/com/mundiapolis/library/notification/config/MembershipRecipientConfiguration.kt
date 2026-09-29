package com.mundiapolis.library.notification.config

import com.mundiapolis.library.notification.adapter.outbound.membership.MembershipNotificationRecipientResolver
import com.mundiapolis.library.notification.service.NotificationRecipientResolver
import io.micrometer.observation.ObservationRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.util.LinkedMultiValueMap
import tools.jackson.databind.ObjectMapper
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpClient
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.email-worker", name = ["enabled"], havingValue = "true")
class MembershipRecipientConfiguration {
    @Bean
    fun membershipClientRegistration(properties: MembershipRecipientClientProperties): ClientRegistration =
        ClientRegistration.withRegistrationId(REGISTRATION_ID)
            .clientId(properties.clientId)
            .clientSecret(properties.clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope(PROFILE_READ_ANY_SCOPE)
            .tokenUri(properties.tokenUri.toASCIIString())
            .build()

    @Bean
    fun membershipClientRegistrationRepository(
        membershipClientRegistration: ClientRegistration,
    ): ClientRegistrationRepository = InMemoryClientRegistrationRepository(membershipClientRegistration)

    @Bean
    fun membershipAuthorizedClientService(
        registrations: ClientRegistrationRepository,
    ): OAuth2AuthorizedClientService = InMemoryOAuth2AuthorizedClientService(registrations)

    @Bean
    fun membershipTokenRestClient(
        properties: MembershipRecipientClientProperties,
        observationRegistry: ObservationRegistry,
    ): RestClient = restClientBuilder(properties, observationRegistry)
        .requestInterceptor(ResponseSizeLimitInterceptor(properties.maximumResponseBytes))
        .configureMessageConverters { converters ->
            converters.disableDefaults()
            converters.addCustomConverter(FormHttpMessageConverter())
            converters.addCustomConverter(OAuth2AccessTokenResponseHttpMessageConverter())
        }
        .defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
        .build()

    @Bean
    fun membershipTokenResponseClient(
        membershipTokenRestClient: RestClient,
        properties: MembershipRecipientClientProperties,
    ): OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> =
        RestClientClientCredentialsTokenResponseClient().apply {
            setRestClient(membershipTokenRestClient)
            addParametersConverter {
                LinkedMultiValueMap<String, String>().apply {
                    add("audience", properties.audience)
                }
            }
        }

    @Bean
    fun membershipAuthorizedClientManager(
        registrations: ClientRegistrationRepository,
        authorizedClients: OAuth2AuthorizedClientService,
        membershipTokenResponseClient: OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>,
    ): OAuth2AuthorizedClientManager {
        val provider = OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials {
                it.accessTokenResponseClient(membershipTokenResponseClient)
                it.clockSkew(TOKEN_CLOCK_SKEW)
            }
            .build()
        return AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients).apply {
            setAuthorizedClientProvider(provider)
        }
    }

    @Bean
    fun membershipProfileRestClient(
        properties: MembershipRecipientClientProperties,
        observationRegistry: ObservationRegistry,
    ): RestClient = restClientBuilder(properties, observationRegistry)
        .baseUrl(properties.baseUrl.toASCIIString())
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build()

    @Bean
    fun notificationRecipientResolver(
        membershipAuthorizedClientManager: OAuth2AuthorizedClientManager,
        membershipProfileRestClient: RestClient,
        objectMapper: ObjectMapper,
        properties: MembershipRecipientClientProperties,
    ): NotificationRecipientResolver = MembershipNotificationRecipientResolver(
        membershipAuthorizedClientManager,
        membershipProfileRestClient,
        objectMapper,
        properties,
    )

    private fun restClientBuilder(
        properties: MembershipRecipientClientProperties,
        observationRegistry: ObservationRegistry,
    ): RestClient.Builder {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder()
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
    }

    companion object {
        const val REGISTRATION_ID = "notification-membership"
        const val PROFILE_READ_ANY_SCOPE = "membership.profile.read.any"
        const val SERVICE_PRINCIPAL = "notification-email-worker"
        private val TOKEN_CLOCK_SKEW = Duration.ofSeconds(10)
    }
}

private class ResponseSizeLimitInterceptor(
    private val maximumResponseBytes: Int,
) : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val response = execution.execute(request, body)
        if (response.headers.contentLength > maximumResponseBytes) {
            response.close()
            throw IOException("OAuth token response exceeded the configured size limit")
        }
        return BoundedClientHttpResponse(response, maximumResponseBytes)
    }
}

private class BoundedClientHttpResponse(
    private val delegate: ClientHttpResponse,
    maximumResponseBytes: Int,
) : ClientHttpResponse {
    private val boundedBody = SizeLimitedInputStream(delegate.body, maximumResponseBytes.toLong())

    override fun getStatusCode() = delegate.statusCode
    override fun getStatusText(): String = delegate.statusText
    override fun getHeaders(): HttpHeaders = delegate.headers
    override fun getBody(): InputStream = boundedBody
    override fun close() = delegate.close()
}

private class SizeLimitedInputStream(
    input: InputStream,
    private val maximumBytes: Long,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int = super.read().also { if (it >= 0) recordBytes(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) recordBytes(it.toLong()) }

    private fun recordBytes(count: Long) {
        consumed += count
        if (consumed > maximumBytes) throw IOException("OAuth token response exceeded the configured size limit")
    }
}
