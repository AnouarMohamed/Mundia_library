package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.NotificationClientProperties
import com.mundiapolis.library.bff.notification.NotificationClient
import com.mundiapolis.library.bff.notification.NotificationProtocolException
import com.mundiapolis.library.bff.notification.NotificationReadStatusView
import com.mundiapolis.library.bff.notification.UpdateNotificationPreferenceView
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

class NotificationClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: NotificationClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl("https://notification.internal")
        server = MockRestServiceServer.bindTo(builder).build()
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .findAndAddModules()
            .build()
        client = NotificationClient(builder.build(), mapper, properties())
    }

    @Test
    fun `inbox forwards bounded filters with delegated authorization`() {
        server.expect(requestTo("https://notification.internal/api/v1/notifications/me?status=UNREAD&limit=20"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andRespond(withSuccess(VALID_PAGE, MediaType.APPLICATION_JSON))

        val page = client.ownNotifications(authorizedClient(), NotificationReadStatusView.UNREAD, 20, null)

        assertThat(page.memberId).isEqualTo(MEMBER_ID)
        assertThat(page.items.single().notificationId).isEqualTo(NOTIFICATION_ID)
        server.verify()
    }

    @Test
    fun `mark read rejects a mismatched downstream identity`() {
        server.expect(requestTo("https://notification.internal/api/v1/notifications/$NOTIFICATION_ID/read"))
            .andExpect(method(HttpMethod.PATCH))
            .andRespond(withSuccess(READ_ITEM.replace(NOTIFICATION_ID.toString(), UUID.randomUUID().toString()), MediaType.APPLICATION_JSON))

        assertThatThrownBy { client.markRead(authorizedClient(), NOTIFICATION_ID) }
            .isInstanceOf(NotificationProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `preference update preserves strong conditional versioning`() {
        val update = UpdateNotificationPreferenceView(false, true, true, true, true)
        server.expect(requestTo("https://notification.internal/api/v1/notifications/preferences/me"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andExpect(header(HttpHeaders.IF_MATCH, "\"1\""))
            .andExpect(
                content().json(
                    """{"emailEnabled":false,"dueSoonEnabled":true,"overdueEnabled":true,"holdReadyEnabled":true,"accountStatusEnabled":true}""",
                    JsonCompareMode.STRICT,
                ),
            )
            .andRespond(
                withSuccess(PREFERENCE, MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ETAG, "\"2\""),
            )

        val result = client.updateOwnPreference(authorizedClient(), "\"1\"", update)

        assertThat(result.entityTag).isEqualTo("\"2\"")
        assertThat(result.preference.version).isEqualTo(2)
        server.verify()
    }

    private fun authorizedClient(): OAuth2AuthorizedClient {
        val registration = ClientRegistration.withRegistrationId("notification-service")
            .clientId("web-bff-notification")
            .clientSecret("test-only-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .tokenUri("https://issuer.example.test/oauth2/token")
            .build()
        val now = Instant.now()
        return OAuth2AuthorizedClient(
            registration,
            "member",
            OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "delegated-token", now, now.plusSeconds(120)),
        )
    }

    private fun properties() = NotificationClientProperties(
        URI("https://notification.internal"),
        "notification-api",
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        256 * 1024,
        Duration.ofMinutes(5),
    )

    private companion object {
        val MEMBER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val NOTIFICATION_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val VALID_PAGE = """
            {"memberId":"$MEMBER_ID","items":[{"notificationId":"$NOTIFICATION_ID","category":"DUE_SOON","subject":"Due soon","body":"A loan is due soon.","occurredAt":"2026-10-01T00:00:00Z","createdAt":"2026-10-01T00:00:01Z","readAt":null}],"nextCursor":null}
        """.trimIndent()
        val READ_ITEM = """
            {"notificationId":"$NOTIFICATION_ID","category":"DUE_SOON","subject":"Due soon","body":"A loan is due soon.","occurredAt":"2026-10-01T00:00:00Z","createdAt":"2026-10-01T00:00:01Z","readAt":"2026-10-01T00:01:00Z"}
        """.trimIndent()
        val PREFERENCE = """
            {"memberId":"$MEMBER_ID","emailEnabled":false,"dueSoonEnabled":true,"overdueEnabled":true,"holdReadyEnabled":true,"accountStatusEnabled":true,"version":2,"updatedAt":"2026-10-01T00:00:00Z"}
        """.trimIndent()
    }
}
