package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.circulation.CirculationController
import com.mundiapolis.library.bff.circulation.CirculationEligibilityView
import com.mundiapolis.library.bff.circulation.CirculationSelfServiceUseCase
import com.mundiapolis.library.bff.circulation.CirculationRequestBodyLimitFilter
import com.mundiapolis.library.bff.circulation.EligibilityStatusView
import com.mundiapolis.library.bff.circulation.LoanCommandView
import com.mundiapolis.library.bff.circulation.LoanMutationResult
import com.mundiapolis.library.bff.circulation.LoanRequestResult
import com.mundiapolis.library.bff.circulation.LoanStatusView
import com.mundiapolis.library.bff.circulation.RequestLoanView
import com.mundiapolis.library.bff.config.BffProperties
import com.mundiapolis.library.bff.config.SecurityConfiguration
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [CirculationController::class],
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
    CirculationRequestBodyLimitFilter::class,
    CirculationSecurityIntegrationTest.CirculationTestConfiguration::class,
    BffSecurityIntegrationTest.ClientRegistrationTestConfiguration::class,
)
class CirculationSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `circulation eligibility requires a browser session`() {
        mockMvc.perform(get("/api/v1/circulation/eligibility"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `loan request requires both browser authentication and csrf`() {
        val request = post("/api/v1/circulation/loans")
            .with(oidcLogin())
            .header("Idempotency-Key", "browser-request-0001")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"editionId":"$EDITION_ID"}""")

        mockMvc.perform(request)
            .andExpect(status().isForbidden)
    }

    @Test
    fun `loan cancellation and renewal require csrf`() {
        listOf("cancel", "renew").forEach { operation ->
            mockMvc.perform(
                post("/api/v1/circulation/loans/$LOAN_ID/$operation")
                    .with(oidcLogin())
                    .header("Idempotency-Key", "browser-$operation-0001"),
            ).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `caller bound circulation responses are no store`() {
        mockMvc.perform(get("/api/v1/circulation/eligibility").with(oidcLogin()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()))

        mockMvc.perform(
            post("/api/v1/circulation/loans")
                .with(oidcLogin())
                .with(csrf())
                .header("Idempotency-Key", "browser-request-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"editionId":"$EDITION_ID"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()))
    }

    @Test
    fun `oversized circulation command is rejected before deserialization`() {
        mockMvc.perform(
            post("/api/v1/circulation/loans")
                .with(oidcLogin())
                .with(csrf())
                .header("Idempotency-Key", "browser-request-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(16 * 1024 + 1)),
        )
            .andExpect(status().`is`(413))
            .andExpect(jsonPath("$.code").value("payload_too_large"))
    }

    @Test
    fun `loan cancellation and renewal responses are no store and preserve replay state`() {
        mockMvc.perform(
            post("/api/v1/circulation/loans/$LOAN_ID/cancel")
                .with(oidcLogin())
                .with(csrf())
                .header("Idempotency-Key", "browser-cancel-0001"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        mockMvc.perform(
            post("/api/v1/circulation/loans/$LOAN_ID/renew")
                .with(oidcLogin())
                .with(csrf())
                .header("Idempotency-Key", "browser-renew-0001"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.renewalCount").value(1))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CirculationTestConfiguration {
        @Bean
        fun circulationSelfServiceUseCase(): CirculationSelfServiceUseCase = FakeCirculationUseCase()
    }

    private class FakeCirculationUseCase : CirculationSelfServiceUseCase {
        override fun eligibility(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) = CirculationEligibilityView(
            memberId = MEMBER_ID,
            status = EligibilityStatusView.ELIGIBLE,
            reasonCode = null,
            sourceVersion = 0,
            sourceOccurredAt = Instant.parse("2026-09-01T00:00:00Z"),
        )

        override fun requestLoan(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            command: RequestLoanView,
            idempotencyKey: String,
        ) = LoanRequestResult(
            loan = LoanCommandView(
                loanId = UUID.fromString("30000000-0000-0000-0000-000000000001"),
                memberId = MEMBER_ID,
                editionId = command.editionId,
                copyId = null,
                status = LoanStatusView.REQUESTED,
                requestedAt = Instant.parse("2026-09-01T00:00:00Z"),
                checkedOutAt = null,
                dueAt = null,
                returnedAt = null,
                renewalCount = 0,
                version = 0,
            ),
            idempotencyReplayed = false,
        )

        override fun cancelLoan(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            loanId: UUID,
            idempotencyKey: String,
        ) = LoanMutationResult(
            loan = loan(loanId, LoanStatusView.CANCELLED),
            idempotencyReplayed = false,
        )

        override fun renewLoan(
            authentication: OAuth2AuthenticationToken,
            request: HttpServletRequest,
            response: HttpServletResponse,
            loanId: UUID,
            idempotencyKey: String,
        ) = LoanMutationResult(
            loan = loan(loanId, LoanStatusView.ACTIVE),
            idempotencyReplayed = false,
        )

        private fun loan(loanId: UUID, status: LoanStatusView): LoanCommandView {
            val active = status == LoanStatusView.ACTIVE
            return LoanCommandView(
                loanId = loanId,
                memberId = MEMBER_ID,
                editionId = EDITION_ID,
                copyId = if (active) COPY_ID else null,
                status = status,
                requestedAt = Instant.parse("2026-09-01T00:00:00Z"),
                checkedOutAt = if (active) Instant.parse("2026-09-02T00:00:00Z") else null,
                dueAt = if (active) Instant.parse("2026-10-02T00:00:00Z") else null,
                returnedAt = null,
                renewalCount = if (active) 1 else 0,
                version = if (active) 2 else 1,
            )
        }
    }

    private companion object {
        val MEMBER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val EDITION_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val LOAN_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val COPY_ID: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
    }
}
