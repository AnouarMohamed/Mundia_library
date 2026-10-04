package com.mundiapolis.library.bff.config

import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.TokenExchangeOAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.TokenExchangeGrantRequest
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2Token
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.util.LinkedMultiValueMap
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class OAuthClientConfiguration {
    @Bean
    fun oauthTokenRestClient(
        properties: OAuthTokenClientProperties,
        observationRegistry: ObservationRegistry,
    ): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder()
            .requestFactory(requestFactory)
            .requestInterceptor(ResponseSizeLimitInterceptor(properties.maximumResponseBytes))
            .observationRegistry(observationRegistry)
            .withOAuthTokenProtocolSupport()
            .build()
    }

    @Bean
    fun authorizationCodeTokenResponseClient(
        oauthTokenRestClient: RestClient,
    ): OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> =
        RestClientAuthorizationCodeTokenResponseClient().apply {
            setRestClient(oauthTokenRestClient)
        }

    @Bean
    fun refreshTokenResponseClient(
        oauthTokenRestClient: RestClient,
    ): OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> =
        RestClientRefreshTokenTokenResponseClient().apply {
            setRestClient(oauthTokenRestClient)
        }

    @Bean
    fun clientCredentialsTokenResponseClient(
        oauthTokenRestClient: RestClient,
    ): OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> =
        RestClientClientCredentialsTokenResponseClient().apply {
            setRestClient(oauthTokenRestClient)
        }

    @Bean
    fun tokenExchangeTokenResponseClient(
        oauthTokenRestClient: RestClient,
        membership: MembershipClientProperties,
        circulation: CirculationClientProperties,
        notification: NotificationClientProperties,
    ): OAuth2AccessTokenResponseClient<TokenExchangeGrantRequest> =
        RestClientTokenExchangeTokenResponseClient().apply {
            setRestClient(oauthTokenRestClient)
            addParametersConverter { request ->
                LinkedMultiValueMap<String, String>().apply {
                    when (request.clientRegistration.registrationId) {
                        MEMBERSHIP_REGISTRATION -> add("audience", membership.audience)
                        CIRCULATION_REGISTRATION -> add("audience", circulation.audience)
                        NOTIFICATION_REGISTRATION -> add("audience", notification.audience)
                    }
                }
            }
        }

    @Bean
    fun authorizedClientManager(
        registrations: ClientRegistrationRepository,
        authorizedClients: OAuth2AuthorizedClientRepository,
        refreshTokenResponseClient: OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>,
        clientCredentialsTokenResponseClient: OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>,
        tokenExchangeTokenResponseClient: OAuth2AccessTokenResponseClient<TokenExchangeGrantRequest>,
        bff: BffProperties,
    ): OAuth2AuthorizedClientManager {
        validateProviderEndpoints(registrations, bff.deploymentTier)
        val tokenExchange = TokenExchangeOAuth2AuthorizedClientProvider().apply {
            setAccessTokenResponseClient(tokenExchangeTokenResponseClient)
            setClockSkew(TOKEN_CLOCK_SKEW)
            setSubjectTokenResolver { context -> context.requiredSubjectToken() }
        }
        val provider = OAuth2AuthorizedClientProviderBuilder.builder()
            .authorizationCode()
            .refreshToken { it.accessTokenResponseClient(refreshTokenResponseClient) }
            .clientCredentials { it.accessTokenResponseClient(clientCredentialsTokenResponseClient) }
            .provider(tokenExchange)
            .build()

        return DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients).apply {
            setAuthorizedClientProvider(provider)
            setContextAttributesMapper { request -> request.attributes }
        }
    }

    internal fun validateProviderEndpoints(
        registrations: ClientRegistrationRepository,
        deploymentTier: String,
    ) {
        KNOWN_REGISTRATIONS.mapNotNull(registrations::findByRegistrationId).forEach { registration ->
            val issuer = registration.providerDetails.issuerUri?.let(::strictEndpoint)
            val authorization = registration.providerDetails.authorizationUri?.let(::strictEndpoint)
            val token = strictEndpoint(registration.providerDetails.tokenUri)
            require(issuer != null && authorization != null && token != null) {
                "OIDC provider endpoints must be absolute and free of credentials, query, and fragment"
            }
            require(
                deploymentTier == "local" ||
                    listOf(issuer, authorization, token).all { it.scheme == "https" },
            ) { "OIDC provider endpoints must use HTTPS outside local development" }
            require(authorization.sameOriginAs(token)) {
                "OIDC authorization and token endpoints must share an exact origin"
            }
        }
    }

    private fun strictEndpoint(value: String): URI? = runCatching { URI(value) }.getOrNull()
        ?.takeIf {
            it.isAbsolute &&
                !it.host.isNullOrBlank() &&
                it.userInfo == null &&
                it.query == null &&
                it.fragment == null
        }

    private fun URI.sameOriginAs(other: URI): Boolean =
        scheme.equals(other.scheme, ignoreCase = true) &&
            host.equals(other.host, ignoreCase = true) &&
            effectivePort() == other.effectivePort()

    private fun URI.effectivePort(): Int = if (port >= 0) port else if (scheme == "https") 443 else 80

    private fun OAuth2AuthorizationContext.requiredSubjectToken(): OAuth2Token =
        getAttribute(SUBJECT_TOKEN_ATTRIBUTE)
            ?: throw OAuth2AuthorizationException(
                OAuth2Error(
                    "invalid_request",
                    "A subject token is required for delegated authorization",
                    null,
                ),
            )

    companion object {
        const val MEMBERSHIP_REGISTRATION = "membership-service"
        const val CIRCULATION_REGISTRATION = "circulation-service"
        const val NOTIFICATION_REGISTRATION = "notification-service"
        const val SUBJECT_TOKEN_ATTRIBUTE = "mundia.delegation.subject-token"
        private val TOKEN_CLOCK_SKEW: Duration = Duration.ofSeconds(10)
        private val KNOWN_REGISTRATIONS = listOf(
            "institutional",
            "catalog-service",
            MEMBERSHIP_REGISTRATION,
            CIRCULATION_REGISTRATION,
            NOTIFICATION_REGISTRATION,
        )
    }
}

internal fun RestClient.Builder.withOAuthTokenProtocolSupport(): RestClient.Builder =
    configureMessageConverters { converters ->
        converters.disableDefaults()
        converters.addCustomConverter(FormHttpMessageConverter())
        converters.addCustomConverter(OAuth2AccessTokenResponseHttpMessageConverter())
    }.defaultStatusHandler(OAuth2ErrorResponseErrorHandler())

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

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) recordBytes(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) recordBytes(count.toLong())
        return count
    }

    private fun recordBytes(count: Long) {
        consumed += count
        if (consumed > maximumBytes) {
            throw IOException("OAuth token response exceeded the configured size limit")
        }
    }
}
