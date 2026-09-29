package com.mundiapolis.library.notification

import com.mundiapolis.library.notification.config.MembershipRecipientClientProperties
import com.mundiapolis.library.notification.config.MembershipRecipientConfiguration
import com.mundiapolis.library.notification.dto.EmailDeliveryException
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MembershipNotificationRecipientResolverTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `client credentials token is cached and profiles use scoped bearer authorization`() {
        val tokenCalls = AtomicInteger()
        val profileCalls = AtomicInteger()
        val testServer = startServer(
            token = { exchange ->
                tokenCalls.incrementAndGet()
                assertThat(exchange.requestMethod).isEqualTo("POST")
                assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo(EXPECTED_BASIC_AUTH)
                val form = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                assertThat(form).contains("grant_type=client_credentials")
                assertThat(form).contains("scope=membership.profile.read.any")
                assertThat(form).contains("audience=membership-api")
                exchange.respond(
                    200,
                    TOKEN_RESPONSE,
                    "application/json",
                )
            },
            profile = { exchange ->
                profileCalls.incrementAndGet()
                assertThat(exchange.requestMethod).isEqualTo("GET")
                assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer service-token")
                exchange.respond(200, validProfile(MEMBER_ID), "application/json")
            },
        )
        val resolver = resolver(testServer)

        assertThat(resolver.resolve(MEMBER_ID).address).isEqualTo("reader@example.test")
        assertThat(resolver.resolve(MEMBER_ID).address).isEqualTo("reader@example.test")
        assertThat(tokenCalls).hasValue(1)
        assertThat(profileCalls).hasValue(2)
    }

    @Test
    fun `profile identity mismatch fails closed as retryable recipient unavailability`() {
        val testServer = startServer(
            token = { it.respond(200, TOKEN_RESPONSE, "application/json") },
            profile = { it.respond(200, validProfile(UUID.randomUUID()), "application/json") },
        )

        assertThatThrownBy { resolver(testServer).resolve(MEMBER_ID) }
            .isInstanceOfSatisfying(EmailDeliveryException::class.java) {
                assertThat(it.failureCode).isEqualTo(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
            }
    }

    @Test
    fun `missing member is a permanent delivery failure`() {
        val testServer = startServer(
            token = { it.respond(200, TOKEN_RESPONSE, "application/json") },
            profile = { it.respond(404, "{}", "application/problem+json") },
        )

        assertThatThrownBy { resolver(testServer).resolve(MEMBER_ID) }
            .isInstanceOfSatisfying(EmailDeliveryException::class.java) {
                assertThat(it.failureCode).isEqualTo(EmailDeliveryFailureCode.RECIPIENT_NOT_FOUND)
            }
    }

    @Test
    fun `cleartext transport requires explicit local opt in`() {
        assertThat(properties(URI("http://127.0.0.1:8080"), false).isSafeConfiguration).isFalse()
        assertThat(properties(URI("http://127.0.0.1:8080"), true).isSafeConfiguration).isTrue()
    }

    private fun resolver(testServer: HttpServer): com.mundiapolis.library.notification.service.NotificationRecipientResolver {
        val baseUri = URI("http://127.0.0.1:${testServer.address.port}")
        val properties = properties(baseUri, true)
        val configuration = MembershipRecipientConfiguration()
        val observationRegistry = ObservationRegistry.NOOP
        val registration = configuration.membershipClientRegistration(properties)
        val registrations = configuration.membershipClientRegistrationRepository(registration)
        val authorizedClients = configuration.membershipAuthorizedClientService(registrations)
        val tokenRestClient = configuration.membershipTokenRestClient(properties, observationRegistry)
        val tokenResponseClient = configuration.membershipTokenResponseClient(tokenRestClient, properties)
        val manager = configuration.membershipAuthorizedClientManager(
            registrations,
            authorizedClients,
            tokenResponseClient,
        )
        val profileRestClient = configuration.membershipProfileRestClient(properties, observationRegistry)
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .findAndAddModules()
            .build()
        return configuration.notificationRecipientResolver(manager, profileRestClient, mapper, properties)
    }

    private fun startServer(
        token: (HttpExchange) -> Unit,
        profile: (HttpExchange) -> Unit,
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { httpServer ->
        httpServer.createContext("/oauth2/token", token)
        httpServer.createContext("/api/v1/members/$MEMBER_ID/profile", profile)
        httpServer.start()
        server = httpServer
    }

    private fun properties(baseUri: URI, allowInsecure: Boolean) = MembershipRecipientClientProperties(
        enabled = true,
        baseUrl = baseUri,
        tokenUri = baseUri.resolve("/oauth2/token"),
        clientId = CLIENT_ID,
        clientSecret = CLIENT_SECRET,
        audience = "membership-api",
        allowInsecureTransport = allowInsecure,
        connectTimeout = Duration.ofSeconds(2),
        readTimeout = Duration.ofSeconds(5),
        maximumResponseBytes = 8_192,
    )

    private fun validProfile(memberId: UUID) = """
        {
          "memberId": "$memberId",
          "email": "reader@example.test",
          "fullName": "Library Reader",
          "universityId": 12345,
          "status": "APPROVED",
          "role": "USER",
          "createdAt": "2026-01-01T00:00:00Z",
          "updatedAt": "2026-09-29T00:00:00Z"
        }
    """.trimIndent()

    private fun HttpExchange.respond(status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private companion object {
        val MEMBER_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        const val CLIENT_ID = "notification-service"
        const val CLIENT_SECRET = "notification-test-secret-123456789"
        val EXPECTED_BASIC_AUTH: String = "Basic " + Base64.getEncoder()
            .encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(StandardCharsets.UTF_8))
        const val TOKEN_RESPONSE =
            """{"access_token":"service-token","token_type":"Bearer","expires_in":300,"scope":"membership.profile.read.any"}"""
    }
}
