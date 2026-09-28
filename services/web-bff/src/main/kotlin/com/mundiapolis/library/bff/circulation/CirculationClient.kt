package com.mundiapolis.library.bff.circulation

import com.mundiapolis.library.bff.config.CirculationClientProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException

@Component
class CirculationClient(
    private val circulationRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: CirculationClientProperties,
) {
    fun ownEligibility(authorizedClient: OAuth2AuthorizedClient): CirculationEligibilityView =
        exchange {
            circulationRestClient.get()
                .uri("/api/v1/circulation/me/eligibility")
                .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
                .exchange { _, response ->
                    handleStatus(response.statusCode.value())
                    decode(response, CirculationEligibilityView::class.java).also(::validateEligibility)
                }
        }

    fun requestOwnLoan(
        authorizedClient: OAuth2AuthorizedClient,
        request: RequestLoanView,
        idempotencyKey: String,
    ): LoanRequestResult = exchange {
        validateIdempotencyKey(idempotencyKey)
        circulationRestClient.post()
            .uri("/api/v1/circulation/loans/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(DownstreamLoanRequest(request.editionId))
            .exchange { _, response ->
                handleStatus(response.statusCode.value(), expected = 201)
                val replayed = response.headers.getFirst(IDEMPOTENCY_REPLAYED)?.let(::strictBoolean)
                    ?: throw CirculationProtocolException()
                val loan = decode(response, LoanCommandView::class.java)
                validateLoan(loan, request.editionId)
                LoanRequestResult(loan, replayed)
            }
    }

    fun cancelOwnLoan(
        authorizedClient: OAuth2AuthorizedClient,
        loanId: java.util.UUID,
        idempotencyKey: String,
    ): LoanMutationResult = mutateOwnLoan(
        authorizedClient = authorizedClient,
        loanId = loanId,
        operation = "cancel",
        idempotencyKey = idempotencyKey,
        validator = ::validateCancelledLoan,
    )

    fun renewOwnLoan(
        authorizedClient: OAuth2AuthorizedClient,
        loanId: java.util.UUID,
        idempotencyKey: String,
    ): LoanMutationResult = mutateOwnLoan(
        authorizedClient = authorizedClient,
        loanId = loanId,
        operation = "renew",
        idempotencyKey = idempotencyKey,
        validator = ::validateRenewedLoan,
    )

    private fun mutateOwnLoan(
        authorizedClient: OAuth2AuthorizedClient,
        loanId: java.util.UUID,
        operation: String,
        idempotencyKey: String,
        validator: (LoanCommandView, java.util.UUID) -> Unit,
    ): LoanMutationResult = exchange {
        validateIdempotencyKey(idempotencyKey)
        circulationRestClient.post()
            .uri("/api/v1/circulation/loans/me/{loanId}/{operation}", loanId, operation)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                val replayed = response.headers.getFirst(IDEMPOTENCY_REPLAYED)?.let(::strictBoolean)
                    ?: throw CirculationProtocolException()
                val loan = decode(response, LoanCommandView::class.java)
                validator(loan, loanId)
                LoanMutationResult(loan, replayed)
            }
    }

    private fun <T> exchange(operation: () -> T): T {
        try {
            return operation()
        } catch (failure: CirculationClientException) {
            throw failure
        } catch (failure: ResourceAccessException) {
            if (failure.hasTimeoutCause()) throw CirculationTimeoutException(failure)
            throw CirculationUnavailableException(failure)
        } catch (failure: RestClientException) {
            throw CirculationProtocolException(failure)
        }
    }

    private fun handleStatus(status: Int, expected: Int = 200) {
        when (status) {
            expected -> return
            400 -> throw CirculationInvalidRequestException()
            401 -> throw CirculationReauthenticationRequiredException()
            403 -> throw CirculationAccessDeniedException()
            404 -> throw CirculationNotFoundException()
            409 -> throw CirculationConflictException()
            422 -> throw CirculationIneligibleException()
            in RETRYABLE_STATUSES -> throw CirculationUnavailableException()
            else -> throw CirculationProtocolException()
        }
    }

    private fun <T> decode(
        response: org.springframework.http.client.ClientHttpResponse,
        type: Class<T>,
    ): T {
        val mediaType = response.headers.contentType
        if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            throw CirculationProtocolException()
        }
        if (response.headers.contentLength > properties.maximumResponseBytes) {
            throw CirculationProtocolException()
        }
        val body = response.body.readNBytes(properties.maximumResponseBytes + 1)
        if (body.size > properties.maximumResponseBytes) throw CirculationProtocolException()
        return runCatching { objectMapper.readValue(body, type) }
            .getOrElse { failure -> throw CirculationProtocolException(failure) }
    }

    private fun validateEligibility(view: CirculationEligibilityView) {
        if (
            view.sourceVersion < 0 ||
            (view.reasonCode != null && !REASON_CODE.matches(view.reasonCode)) ||
            (view.status == EligibilityStatusView.ELIGIBLE) != (view.reasonCode == null)
        ) {
            throw CirculationProtocolException()
        }
    }

    private fun validateLoan(view: LoanCommandView, requestedEditionId: java.util.UUID) {
        if (
            view.editionId != requestedEditionId ||
            view.status != LoanStatusView.REQUESTED ||
            view.copyId != null ||
            view.checkedOutAt != null ||
            view.dueAt != null ||
            view.returnedAt != null ||
            view.renewalCount != 0 ||
            view.version != 0L
        ) {
            throw CirculationProtocolException()
        }
    }

    private fun validateCancelledLoan(view: LoanCommandView, requestedLoanId: java.util.UUID) {
        if (
            view.loanId != requestedLoanId ||
            view.status != LoanStatusView.CANCELLED ||
            view.copyId != null ||
            view.checkedOutAt != null ||
            view.dueAt != null ||
            view.returnedAt != null ||
            view.renewalCount != 0 ||
            view.version < 1
        ) {
            throw CirculationProtocolException()
        }
    }

    private fun validateRenewedLoan(view: LoanCommandView, requestedLoanId: java.util.UUID) {
        val checkedOutAt = view.checkedOutAt
        val dueAt = view.dueAt
        if (
            view.loanId != requestedLoanId ||
            view.status != LoanStatusView.ACTIVE ||
            view.copyId == null ||
            checkedOutAt == null ||
            dueAt == null ||
            view.returnedAt != null ||
            view.renewalCount < 1 ||
            view.version < 2 ||
            checkedOutAt < view.requestedAt ||
            dueAt <= checkedOutAt
        ) {
            throw CirculationProtocolException()
        }
    }

    private fun validateIdempotencyKey(value: String) {
        if (value.length !in 16..128 || value.any { it.code !in 0x21..0x7e }) {
            throw CirculationInvalidRequestException()
        }
    }

    private fun strictBoolean(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw CirculationProtocolException()
    }

    private fun bearer(client: OAuth2AuthorizedClient): String =
        "Bearer ${client.accessToken.tokenValue}"

    private fun Throwable.hasTimeoutCause(): Boolean = generateSequence(this) { it.cause }
        .any { it is HttpTimeoutException || it is SocketTimeoutException }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
        val REASON_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }
}

sealed class CirculationClientException(cause: Throwable? = null) : RuntimeException(cause)
sealed class CirculationAuthorizationRejectedException(cause: Throwable? = null) :
    CirculationClientException(cause)

class CirculationReauthenticationRequiredException(cause: Throwable? = null) :
    CirculationAuthorizationRejectedException(cause)
class CirculationAccessDeniedException(cause: Throwable? = null) : CirculationAuthorizationRejectedException(cause)
class CirculationInvalidRequestException(cause: Throwable? = null) : CirculationClientException(cause)
class CirculationNotFoundException(cause: Throwable? = null) : CirculationClientException(cause)
class CirculationConflictException(cause: Throwable? = null) : CirculationClientException(cause)
class CirculationIneligibleException(cause: Throwable? = null) : CirculationClientException(cause)
class CirculationTimeoutException(cause: Throwable? = null) : CirculationClientException(cause)
class CirculationUnavailableException(cause: Throwable? = null) : CirculationClientException(cause)
class CirculationProtocolException(cause: Throwable? = null) : CirculationClientException(cause)
