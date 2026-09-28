package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.circulation.CirculationClient
import com.mundiapolis.library.bff.circulation.CirculationProtocolException
import com.mundiapolis.library.bff.circulation.LoanStatusView
import com.mundiapolis.library.bff.circulation.RequestLoanView
import com.mundiapolis.library.bff.circulation.RequestReservationView
import com.mundiapolis.library.bff.config.CirculationClientProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CirculationClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: CirculationClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl("https://circulation.internal")
        server = MockRestServiceServer.bindTo(builder).build()
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .findAndAddModules()
            .build()
        client = CirculationClient(builder.build(), mapper, properties())
    }

    @Test
    fun `eligibility uses the fixed me route and delegated bearer token`() {
        server.expect(requestTo("https://circulation.internal/api/v1/circulation/me/eligibility"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andRespond(withSuccess(VALID_ELIGIBILITY, MediaType.APPLICATION_JSON))

        val eligibility = client.ownEligibility(authorizedClient())

        assertThat(eligibility.status.name).isEqualTo("ELIGIBLE")
        assertThat(eligibility.sourceVersion).isZero()
        server.verify()
    }

    @Test
    fun `loan history forwards bounded filters and validates caller consistency`() {
        server.expect(
            requestTo(
                "https://circulation.internal/api/v1/circulation/loans/me" +
                    "?status=ACTIVE&limit=10&cursor=next_page-1",
            ),
        )
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andRespond(withSuccess(VALID_LOAN_PAGE, MediaType.APPLICATION_JSON))

        val page = client.ownLoans(
            authorizedClient(), LoanStatusView.ACTIVE, 10, "next_page-1",
        )

        assertThat(page.memberId).isEqualTo(MEMBER_ID)
        assertThat(page.items).hasSize(1)
        server.verify()
    }

    @Test
    fun `reservation history rejects a cross member item`() {
        server.expect(requestTo("https://circulation.internal/api/v1/circulation/reservations/me?limit=20"))
            .andRespond(
                withSuccess(
                    CROSS_MEMBER_RESERVATION_PAGE,
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThatThrownBy {
            client.ownReservations(authorizedClient(), null, 20, null)
        }.isInstanceOf(CirculationProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `loan request sends only edition id and preserves actor scoped idempotency`() {
        server.expect(requestTo("https://circulation.internal/api/v1/circulation/loans/me"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andExpect(content().json("""{"editionId":"$EDITION_ID"}""", JsonCompareMode.STRICT))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "false")
                    .body(VALID_LOAN),
            )

        val result = client.requestOwnLoan(
            authorizedClient(),
            RequestLoanView(EDITION_ID),
            IDEMPOTENCY_KEY,
        )

        assertThat(result.idempotencyReplayed).isFalse()
        assertThat(result.loan.memberId).isEqualTo(MEMBER_ID)
        server.verify()
    }

    @Test
    fun `loan response with a mismatched edition is rejected`() {
        server.expect(requestTo("https://circulation.internal/api/v1/circulation/loans/me"))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "false")
                    .body(VALID_LOAN.replace(EDITION_ID.toString(), UUID.randomUUID().toString())),
            )

        assertThatThrownBy {
            client.requestOwnLoan(authorizedClient(), RequestLoanView(EDITION_ID), IDEMPOTENCY_KEY)
        }.isInstanceOf(CirculationProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `loan cancellation uses the fixed me route and validates terminal state`() {
        server.expect(
            requestTo("https://circulation.internal/api/v1/circulation/loans/me/$LOAN_ID/cancel"),
        )
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andRespond(
                withSuccess(VALID_CANCELLED_LOAN, MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "false"),
            )

        val result = client.cancelOwnLoan(authorizedClient(), LOAN_ID, IDEMPOTENCY_KEY)

        assertThat(result.loan.status.name).isEqualTo("CANCELLED")
        assertThat(result.idempotencyReplayed).isFalse()
        server.verify()
    }

    @Test
    fun `loan renewal uses the fixed me route and validates active lifecycle`() {
        server.expect(
            requestTo("https://circulation.internal/api/v1/circulation/loans/me/$LOAN_ID/renew"),
        )
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andRespond(
                withSuccess(VALID_RENEWED_LOAN, MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "true"),
            )

        val result = client.renewOwnLoan(authorizedClient(), LOAN_ID, IDEMPOTENCY_KEY)

        assertThat(result.loan.renewalCount).isOne()
        assertThat(result.idempotencyReplayed).isTrue()
        server.verify()
    }

    @Test
    fun `renewal response with an impossible lifecycle is rejected`() {
        server.expect(
            requestTo("https://circulation.internal/api/v1/circulation/loans/me/$LOAN_ID/renew"),
        ).andRespond(
            withSuccess(
                VALID_RENEWED_LOAN.replace(
                    "2026-10-02T00:00:00Z",
                    "2026-09-01T12:00:00Z",
                ),
                MediaType.APPLICATION_JSON,
            ).header("Idempotency-Replayed", "false"),
        )

        assertThatThrownBy {
            client.renewOwnLoan(authorizedClient(), LOAN_ID, IDEMPOTENCY_KEY)
        }.isInstanceOf(CirculationProtocolException::class.java)
        server.verify()
    }

    @Test
    fun `reservation placement sends only edition id to the fixed me route`() {
        server.expect(requestTo("https://circulation.internal/api/v1/circulation/reservations/me"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andExpect(content().json("""{"editionId":"$EDITION_ID"}""", JsonCompareMode.STRICT))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "false")
                    .body(VALID_WAITING_RESERVATION),
            )

        val result = client.placeOwnReservation(
            authorizedClient(),
            RequestReservationView(EDITION_ID),
            IDEMPOTENCY_KEY,
        )

        assertThat(result.reservation.status.name).isEqualTo("WAITING")
        assertThat(result.idempotencyReplayed).isFalse()
        server.verify()
    }

    @Test
    fun `reservation cancellation validates identity and terminal lifecycle`() {
        server.expect(
            requestTo(
                "https://circulation.internal/api/v1/circulation/reservations/me/" +
                    "$RESERVATION_ID/cancel",
            ),
        )
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
            .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andRespond(
                withSuccess(VALID_CANCELLED_RESERVATION, MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "true"),
            )

        val result = client.cancelOwnReservation(
            authorizedClient(),
            RESERVATION_ID,
            IDEMPOTENCY_KEY,
        )

        assertThat(result.reservation.status.name).isEqualTo("CANCELLED")
        assertThat(result.idempotencyReplayed).isTrue()
        server.verify()
    }

    @Test
    fun `reservation response with a mismatched edition is rejected`() {
        server.expect(requestTo("https://circulation.internal/api/v1/circulation/reservations/me"))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "false")
                    .body(
                        VALID_WAITING_RESERVATION.replace(
                            EDITION_ID.toString(),
                            UUID.randomUUID().toString(),
                        ),
                    ),
            )

        assertThatThrownBy {
            client.placeOwnReservation(
                authorizedClient(),
                RequestReservationView(EDITION_ID),
                IDEMPOTENCY_KEY,
            )
        }.isInstanceOf(CirculationProtocolException::class.java)
        server.verify()
    }

    private fun authorizedClient(): OAuth2AuthorizedClient {
        val registration = ClientRegistration.withRegistrationId("circulation-service")
            .clientId("web-bff-circulation")
            .clientSecret("test-only-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .tokenUri("https://issuer.example.test/oauth2/token")
            .build()
        val now = Instant.now()
        val token = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "delegated-token",
            now,
            now.plusSeconds(120),
        )
        return OAuth2AuthorizedClient(registration, "member", token)
    }

    private fun properties() = CirculationClientProperties(
        baseUrl = URI("https://circulation.internal"),
        audience = "circulation-api",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(5),
        maximumResponseBytes = 64 * 1024,
        maximumDelegatedTokenLifetime = Duration.ofMinutes(5),
    )

    private companion object {
        val MEMBER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val EDITION_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val LOAN_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val RESERVATION_ID: UUID = UUID.fromString("50000000-0000-0000-0000-000000000001")
        const val IDEMPOTENCY_KEY = "loan-request-00000001"
        val VALID_ELIGIBILITY = """
            {
              "memberId": "$MEMBER_ID",
              "status": "ELIGIBLE",
              "reasonCode": null,
              "sourceVersion": 0,
              "sourceOccurredAt": "2026-09-01T00:00:00Z"
            }
        """.trimIndent()
        val VALID_LOAN_PAGE = """
            {
              "memberId": "$MEMBER_ID",
              "items": [{
                "loanId": "$LOAN_ID",
                "memberId": "$MEMBER_ID",
                "editionId": "$EDITION_ID",
                "copyId": "40000000-0000-0000-0000-000000000001",
                "status": "ACTIVE",
                "requestedAt": "2026-09-01T00:00:00Z",
                "checkedOutAt": "2026-09-02T00:00:00Z",
                "dueAt": "2026-10-02T00:00:00Z",
                "returnedAt": null,
                "rejectedAt": null,
                "renewalCount": 0,
                "version": 1
              }],
              "nextCursor": null
            }
        """.trimIndent()
        val CROSS_MEMBER_RESERVATION_PAGE = """
            {
              "memberId": "$MEMBER_ID",
              "items": [{
                "reservationId": "$RESERVATION_ID",
                "memberId": "90000000-0000-0000-0000-000000000001",
                "editionId": "$EDITION_ID",
                "copyId": null,
                "status": "WAITING",
                "placedAt": "2026-09-01T00:00:00Z",
                "readyAt": null,
                "expiresAt": null,
                "fulfilledAt": null,
                "cancelledAt": null,
                "version": 0
              }],
              "nextCursor": null
            }
        """.trimIndent()
        val VALID_LOAN = """
            {
              "loanId": "30000000-0000-0000-0000-000000000001",
              "memberId": "$MEMBER_ID",
              "editionId": "$EDITION_ID",
              "copyId": null,
              "status": "REQUESTED",
              "requestedAt": "2026-09-01T00:00:00Z",
              "checkedOutAt": null,
              "dueAt": null,
              "returnedAt": null,
              "renewalCount": 0,
              "version": 0
            }
        """.trimIndent()
        val VALID_CANCELLED_LOAN = """
            {
              "loanId": "$LOAN_ID",
              "memberId": "$MEMBER_ID",
              "editionId": "$EDITION_ID",
              "copyId": null,
              "status": "CANCELLED",
              "requestedAt": "2026-09-01T00:00:00Z",
              "checkedOutAt": null,
              "dueAt": null,
              "returnedAt": null,
              "renewalCount": 0,
              "version": 1
            }
        """.trimIndent()
        val VALID_RENEWED_LOAN = """
            {
              "loanId": "$LOAN_ID",
              "memberId": "$MEMBER_ID",
              "editionId": "$EDITION_ID",
              "copyId": "40000000-0000-0000-0000-000000000001",
              "status": "ACTIVE",
              "requestedAt": "2026-09-01T00:00:00Z",
              "checkedOutAt": "2026-09-02T00:00:00Z",
              "dueAt": "2026-10-02T00:00:00Z",
              "returnedAt": null,
              "renewalCount": 1,
              "version": 2
            }
        """.trimIndent()
        val VALID_WAITING_RESERVATION = """
            {
              "reservationId": "$RESERVATION_ID",
              "memberId": "$MEMBER_ID",
              "editionId": "$EDITION_ID",
              "copyId": null,
              "status": "WAITING",
              "placedAt": "2026-09-01T00:00:00Z",
              "readyAt": null,
              "expiresAt": null,
              "fulfilledAt": null,
              "cancelledAt": null,
              "version": 0
            }
        """.trimIndent()
        val VALID_CANCELLED_RESERVATION = """
            {
              "reservationId": "$RESERVATION_ID",
              "memberId": "$MEMBER_ID",
              "editionId": "$EDITION_ID",
              "copyId": null,
              "status": "CANCELLED",
              "placedAt": "2026-09-01T00:00:00Z",
              "readyAt": null,
              "expiresAt": null,
              "fulfilledAt": null,
              "cancelledAt": "2026-09-02T00:00:00Z",
              "version": 1
            }
        """.trimIndent()
    }
}
