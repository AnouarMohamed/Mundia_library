package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import com.mundiapolis.library.bff.membership.AccountStatusView
import com.mundiapolis.library.bff.membership.AdminMemberPageView
import com.mundiapolis.library.bff.membership.AdminMemberSummaryView
import com.mundiapolis.library.bff.membership.ChangeMemberStatusView
import com.mundiapolis.library.bff.membership.MembershipAdminController
import com.mundiapolis.library.bff.membership.MembershipAdminUseCase
import com.mundiapolis.library.bff.membership.MembershipCommandView
import com.mundiapolis.library.bff.membership.MembershipMutationResult
import com.mundiapolis.library.bff.membership.MembershipRoleView
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [MembershipAdminController::class],
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
    MembershipAdminSecurityIntegrationTest.AdminTestConfiguration::class,
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class MembershipAdminSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `administrative queue requires a browser session and is never cached`() {
        mockMvc.perform(get("/api/v1/admin/members"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/v1/admin/members").with(oidcLogin()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.items[0].aggregateVersion").value(0))
    }

    @Test
    fun `administrative decisions require csrf and preserve concurrency headers`() {
        val request = post("/api/v1/admin/members/$MEMBER_ID/status")
            .with(oidcLogin())
            .header("If-Match", "\"0\"")
            .header("Idempotency-Key", "admin-decision-00000001")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"status":"APPROVED","reason":"Identity evidence verified"}""")

        mockMvc.perform(request).andExpect(status().isForbidden)
        mockMvc.perform(request.with(csrf()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("APPROVED"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class AdminTestConfiguration {
        @Bean
        fun membershipAdminUseCase(): MembershipAdminUseCase = object : MembershipAdminUseCase {
            override fun members(
                authentication: org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken,
                request: jakarta.servlet.http.HttpServletRequest,
                response: jakarta.servlet.http.HttpServletResponse,
                status: AccountStatusView,
                limit: Int?,
                cursor: String?,
            ) = AdminMemberPageView(listOf(member()), null)

            override fun changeStatus(
                authentication: org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken,
                request: jakarta.servlet.http.HttpServletRequest,
                response: jakarta.servlet.http.HttpServletResponse,
                memberId: UUID,
                expectedVersion: Long,
                idempotencyKey: String,
                command: ChangeMemberStatusView,
            ) = MembershipMutationResult(
                MembershipCommandView(memberId, 1, command.status, NOW, false),
                idempotencyReplayed = false,
            )
        }
    }

    companion object {
        val MEMBER_ID: UUID = UUID.fromString("13000000-0000-4000-8000-000000000001")
        val NOW: Instant = Instant.parse("2026-10-07T10:00:00Z")

        fun member() = AdminMemberSummaryView(
            MEMBER_ID,
            "pending.student@mundiapolis.ma",
            "Pending Student",
            20_260_001,
            AccountStatusView.PENDING,
            MembershipRoleView.USER,
            0,
            NOW,
            NOW,
        )
    }
}
