package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import com.mundiapolis.library.bff.notification.NotificationCategoryView
import com.mundiapolis.library.bff.notification.NotificationController
import com.mundiapolis.library.bff.notification.NotificationItemView
import com.mundiapolis.library.bff.notification.NotificationPageView
import com.mundiapolis.library.bff.notification.NotificationPreferenceView
import com.mundiapolis.library.bff.notification.NotificationReadStatusView
import com.mundiapolis.library.bff.notification.NotificationSelfServiceUseCase
import com.mundiapolis.library.bff.notification.UpdateNotificationPreferenceView
import com.mundiapolis.library.bff.notification.VersionedNotificationPreference
import com.mundiapolis.library.bff.security.ApiRequestBodyLimitFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [NotificationController::class],
    properties = [
        "app.bff.deployment-tier=local",
        "app.bff.public-base-url=http://localhost",
        "app.bff.secure-cookies=false",
        "app.bff.session-maximum-age=PT8H",
    ],
)
@EnableConfigurationProperties(BffProperties::class)
@Import(
    SecurityConfiguration::class,
    ApiRequestBodyLimitFilter::class,
    NotificationSecurityIntegrationTest.NotificationTestConfiguration::class,
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class NotificationSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `notification routes require a browser session`() {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `notification mutations require csrf`() {
        mockMvc.perform(patch("/api/v1/notifications/$NOTIFICATION_ID/read").with(oidcLogin()))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            put("/api/v1/notifications/preferences")
                .with(oidcLogin())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `notification responses are private and preserve strong versions`() {
        mockMvc.perform(get("/api/v1/notifications?status=UNREAD&limit=20").with(oidcLogin()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items[0].notificationId").value(NOTIFICATION_ID.toString()))

        mockMvc.perform(
            patch("/api/v1/notifications/$NOTIFICATION_ID/read")
                .with(oidcLogin())
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.readAt").exists())

        mockMvc.perform(get("/api/v1/notifications/preferences").with(oidcLogin()))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))

        mockMvc.perform(
            put("/api/v1/notifications/preferences")
                .with(oidcLogin())
                .with(csrf())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.emailEnabled").value(false))
    }

    @Test
    fun `preference update requires an explicit current version`() {
        mockMvc.perform(
            put("/api/v1/notifications/preferences")
                .with(oidcLogin())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE),
        )
            .andExpect(status().isPreconditionRequired)
            .andExpect(jsonPath("$.code").value("notification_precondition_required"))
    }

    @Test
    fun `preference update rejects oversized bodies before parsing`() {
        mockMvc.perform(
            put("/api/v1/notifications/preferences")
                .with(oidcLogin())
                .with(csrf())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(16 * 1024 + 1)),
        )
            .andExpect(status().`is`(413))
            .andExpect(jsonPath("$.code").value("payload_too_large"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class NotificationTestConfiguration {
        @Bean
        fun notificationSelfServiceUseCase(): NotificationSelfServiceUseCase = FakeNotificationUseCase()
    }

    private class FakeNotificationUseCase : NotificationSelfServiceUseCase {
        override fun notifications(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            status: NotificationReadStatusView?,
            limit: Int?,
            cursor: String?,
        ) = NotificationPageView(MEMBER_ID, listOf(item(null)), null)

        override fun markRead(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            notificationId: UUID,
        ) = item(Instant.parse("2026-10-01T00:01:00Z"))

        override fun preference(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) = VersionedNotificationPreference(preference(true, 1), "\"1\"")

        override fun updatePreference(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            entityTag: String,
            update: UpdateNotificationPreferenceView,
        ) = VersionedNotificationPreference(preference(update.emailEnabled, 2), "\"2\"")

        private fun item(readAt: Instant?) = NotificationItemView(
            NOTIFICATION_ID,
            NotificationCategoryView.DUE_SOON,
            "Due soon",
            "A loan is due soon.",
            Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:01Z"),
            readAt,
        )

        private fun preference(emailEnabled: Boolean, version: Long) =
            NotificationPreferenceView(MEMBER_ID, emailEnabled, true, true, true, true, version, Instant.now())
    }

    private companion object {
        val MEMBER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val NOTIFICATION_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        const val UPDATE = """{"emailEnabled":false,"dueSoonEnabled":true,"overdueEnabled":true,"holdReadyEnabled":true,"accountStatusEnabled":true}"""
    }
}
