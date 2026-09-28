package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
import com.mundiapolis.library.bff.membership.AccountStatusView
import com.mundiapolis.library.bff.membership.MemberProfileView
import com.mundiapolis.library.bff.membership.MembershipProfileController
import com.mundiapolis.library.bff.membership.MembershipProfileUseCase
import com.mundiapolis.library.bff.membership.MembershipRoleView
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [MembershipProfileController::class],
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
    MembershipProfileSecurityIntegrationTest.ProfileTestConfiguration::class,
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class MembershipProfileSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `profile requires a browser session`() {
        mockMvc.perform(get("/api/v1/membership/profile"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `profile returns a no-store caller-bound projection`() {
        mockMvc.perform(get("/api/v1/membership/profile").with(oidcLogin()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.memberId").value("10000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.email").value("member@example.test"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ProfileTestConfiguration {
        @Bean
        fun membershipProfileUseCase(): MembershipProfileUseCase = MembershipProfileUseCase { _, _, _ ->
            MemberProfileView(
                memberId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
                email = "member@example.test",
                fullName = "Library Member",
                universityId = 90_000_001,
                status = AccountStatusView.APPROVED,
                role = MembershipRoleView.USER,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-09-01T00:00:00Z"),
            )
        }
    }
}
