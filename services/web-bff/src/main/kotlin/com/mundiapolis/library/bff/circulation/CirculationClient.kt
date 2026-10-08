package com.mundiapolis.library.bff.circulation

import com.mundiapolis.library.bff.config.CirculationClientProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriComponentsBuilder
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

    fun ownLoans(
        authorizedClient: OAuth2AuthorizedClient,
        status: LoanStatusView?,
        limit: Int?,
        cursor: String?,
    ): MemberLoanPageView = exchange {
        val pageSize = validatePageRequest(limit, cursor)
        circulationRestClient.get()
            .uri(historyUri("/api/v1/circulation/loans/me", status?.name, limit, cursor))
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, MemberLoanPageView::class.java).also {
                    validateLoanPage(it, pageSize)
                }
            }
    }

    fun ownReservations(
        authorizedClient: OAuth2AuthorizedClient,
        status: ReservationStatusView?,
        limit: Int?,
        cursor: String?,
    ): MemberReservationPageView = exchange {
        val pageSize = validatePageRequest(limit, cursor)
        circulationRestClient.get()
            .uri(historyUri("/api/v1/circulation/reservations/me", status?.name, limit, cursor))
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, MemberReservationPageView::class.java).also {
                    validateReservationPage(it, pageSize)
                }
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

    fun placeOwnReservation(
        authorizedClient: OAuth2AuthorizedClient,
        request: RequestReservationView,
        idempotencyKey: String,
    ): ReservationCommandResult = exchange {
        validateIdempotencyKey(idempotencyKey)
        circulationRestClient.post()
            .uri("/api/v1/circulation/reservations/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(DownstreamReservationRequest(request.editionId))
            .exchange { _, response ->
                handleStatus(response.statusCode.value(), expected = 201)
                reservationResult(response) { reservation ->
                    validatePlacedReservation(reservation, request.editionId)
                }
            }
    }

    fun cancelOwnReservation(
        authorizedClient: OAuth2AuthorizedClient,
        reservationId: java.util.UUID,
        idempotencyKey: String,
    ): ReservationCommandResult = exchange {
        validateIdempotencyKey(idempotencyKey)
        circulationRestClient.post()
            .uri("/api/v1/circulation/reservations/me/{reservationId}/cancel", reservationId)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                reservationResult(response) { reservation ->
                    validateCancelledReservation(reservation, reservationId)
                }
            }
    }

    fun administrativeOverview(
        authorizedClient: OAuth2AuthorizedClient,
    ): AdministrativeCirculationOverviewView = exchange {
        circulationRestClient.get()
            .uri("/api/v1/circulation/admin/overview")
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, AdministrativeCirculationOverviewView::class.java).also(::validateOverview)
            }
    }

    fun administrativeLoans(
        authorizedClient: OAuth2AuthorizedClient,
        status: LoanStatusView,
        limit: Int?,
        cursor: String?,
    ): AdministrativeLoanPageView = exchange {
        val pageSize = validatePageRequest(limit, cursor)
        circulationRestClient.get()
            .uri(historyUri("/api/v1/circulation/admin/loans", status.name, limit, cursor))
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, AdministrativeLoanPageView::class.java).also {
                    if (it.items.size > pageSize || it.items.any { loan -> loan.status != status || !loan.hasValidLifecycle() } ||
                        !it.items.isStrictlyOrderedBy { loan -> loan.requestedAt to loan.loanId } ||
                        !validNextCursor(it.nextCursor)) {
                        throw CirculationProtocolException()
                    }
                }
            }
    }

    fun administrativeReservations(
        authorizedClient: OAuth2AuthorizedClient,
        status: ReservationStatusView,
        limit: Int?,
        cursor: String?,
    ): AdministrativeReservationPageView = exchange {
        val pageSize = validatePageRequest(limit, cursor)
        circulationRestClient.get()
            .uri(historyUri("/api/v1/circulation/admin/reservations", status.name, limit, cursor))
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                decode(response, AdministrativeReservationPageView::class.java).also {
                    if (it.items.size > pageSize || it.items.any { reservation -> reservation.status != status || !reservation.hasValidLifecycle() } ||
                        !it.items.isStrictlyOrderedBy { reservation -> reservation.placedAt to reservation.reservationId } ||
                        !validNextCursor(it.nextCursor)) {
                        throw CirculationProtocolException()
                    }
                }
            }
    }

    fun mutateAdministrativeLoan(
        authorizedClient: OAuth2AuthorizedClient,
        loanId: java.util.UUID,
        operation: String,
        idempotencyKey: String,
    ): LoanMutationResult = exchange {
        validateIdempotencyKey(idempotencyKey)
        if (operation !in ADMIN_LOAN_OPERATIONS) throw CirculationInvalidRequestException()
        circulationRestClient.post()
            .uri("/api/v1/circulation/loans/{loanId}/{operation}", loanId, operation)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                val replayed = response.headers.getFirst(IDEMPOTENCY_REPLAYED)?.let(::strictBoolean)
                    ?: throw CirculationProtocolException()
                val loan = decode(response, LoanCommandView::class.java)
                if (loan.loanId != loanId || loan.status != ADMIN_LOAN_RESULTS.getValue(operation)) {
                    throw CirculationProtocolException()
                }
                LoanMutationResult(loan, replayed)
            }
    }

    fun mutateAdministrativeReservation(
        authorizedClient: OAuth2AuthorizedClient,
        reservationId: java.util.UUID,
        operation: String,
        idempotencyKey: String,
    ): ReservationCommandResult = exchange {
        validateIdempotencyKey(idempotencyKey)
        if (operation !in ADMIN_RESERVATION_OPERATIONS) throw CirculationInvalidRequestException()
        circulationRestClient.post()
            .uri("/api/v1/circulation/reservations/{reservationId}/{operation}", reservationId, operation)
            .header(HttpHeaders.AUTHORIZATION, bearer(authorizedClient))
            .header(IDEMPOTENCY_KEY, idempotencyKey)
            .exchange { _, response ->
                handleStatus(response.statusCode.value())
                reservationResult(response) { reservation ->
                    if (reservation.reservationId != reservationId ||
                        reservation.status != ADMIN_RESERVATION_RESULTS.getValue(operation)) {
                        throw CirculationProtocolException()
                    }
                }
            }
    }

    private fun reservationResult(
        response: org.springframework.http.client.ClientHttpResponse,
        validator: (ReservationCommandView) -> Unit,
    ): ReservationCommandResult {
        val replayed = response.headers.getFirst(IDEMPOTENCY_REPLAYED)?.let(::strictBoolean)
            ?: throw CirculationProtocolException()
        val reservation = decode(response, ReservationCommandView::class.java)
        validator(reservation)
        return ReservationCommandResult(reservation, replayed)
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

    private fun validateOverview(view: AdministrativeCirculationOverviewView) {
        if (listOf(
                view.requestedLoans,
                view.activeLoans,
                view.overdueLoans,
                view.waitingReservations,
                view.readyReservations,
            ).any { it < 0 } || view.overdueLoans > view.activeLoans
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

    private fun validatePlacedReservation(
        view: ReservationCommandView,
        requestedEditionId: java.util.UUID,
    ) {
        if (view.editionId != requestedEditionId) {
            throw CirculationProtocolException()
        }
        when (view.status) {
            ReservationStatusView.WAITING -> if (
                view.copyId != null || view.readyAt != null || view.expiresAt != null ||
                view.fulfilledAt != null || view.cancelledAt != null || view.version != 0L
            ) {
                throw CirculationProtocolException()
            }

            ReservationStatusView.READY -> if (!view.hasValidReadyState() || view.version != 1L) {
                throw CirculationProtocolException()
            }

            else -> throw CirculationProtocolException()
        }
    }

    private fun validateCancelledReservation(
        view: ReservationCommandView,
        requestedReservationId: java.util.UUID,
    ) {
        if (
            view.reservationId != requestedReservationId ||
            view.status != ReservationStatusView.CANCELLED ||
            view.fulfilledAt != null ||
            view.cancelledAt == null ||
            view.cancelledAt < view.placedAt ||
            view.version < 1 ||
            !view.hasValidOpenStateShape()
        ) {
            throw CirculationProtocolException()
        }
    }

    private fun ReservationCommandView.hasValidReadyState(): Boolean =
        copyId != null && readyAt != null && expiresAt != null &&
            readyAt >= placedAt && expiresAt > readyAt &&
            fulfilledAt == null && cancelledAt == null

    private fun ReservationCommandView.hasValidOpenStateShape(): Boolean =
        (copyId == null && readyAt == null && expiresAt == null) ||
            (copyId != null && readyAt != null && expiresAt != null &&
                readyAt >= placedAt && expiresAt > readyAt)

    private fun historyUri(path: String, status: String?, limit: Int?, cursor: String?): String {
        val builder = UriComponentsBuilder.fromPath(path)
        status?.let { builder.queryParam("status", it) }
        limit?.let { builder.queryParam("limit", it) }
        cursor?.let { builder.queryParam("cursor", it) }
        return builder.build().encode().toUriString()
    }

    private fun validatePageRequest(limit: Int?, cursor: String?): Int {
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAX_PAGE_SIZE) throw CirculationInvalidRequestException()
        if (cursor != null && (cursor.isBlank() || !CURSOR.matches(cursor))) {
            throw CirculationInvalidRequestException()
        }
        return pageSize
    }

    private fun validateLoanPage(page: MemberLoanPageView, pageSize: Int) {
        if (
            page.items.size > pageSize ||
            (page.nextCursor != null && page.items.size != pageSize) ||
            !validNextCursor(page.nextCursor)
        ) {
            throw CirculationProtocolException()
        }
        page.items.forEach { loan ->
            if (loan.memberId != page.memberId || !loan.hasValidLifecycle()) {
                throw CirculationProtocolException()
            }
        }
        if (!page.items.isStrictlyOrderedBy { it.requestedAt to it.loanId }) {
            throw CirculationProtocolException()
        }
    }

    private fun validateReservationPage(page: MemberReservationPageView, pageSize: Int) {
        if (
            page.items.size > pageSize ||
            (page.nextCursor != null && page.items.size != pageSize) ||
            !validNextCursor(page.nextCursor)
        ) {
            throw CirculationProtocolException()
        }
        page.items.forEach { reservation ->
            if (reservation.memberId != page.memberId || !reservation.hasValidLifecycle()) {
                throw CirculationProtocolException()
            }
        }
        if (!page.items.isStrictlyOrderedBy { it.placedAt to it.reservationId }) {
            throw CirculationProtocolException()
        }
    }

    private fun LoanHistoryItemView.hasValidLifecycle(): Boolean = version >= 0 && renewalCount >= 0 &&
        when (status) {
            LoanStatusView.REQUESTED, LoanStatusView.CANCELLED ->
                copyId == null && checkedOutAt == null && dueAt == null && returnedAt == null &&
                    rejectedAt == null
            LoanStatusView.ACTIVE -> copyId != null && checkedOutAt != null && dueAt != null &&
                dueAt > checkedOutAt && returnedAt == null && rejectedAt == null
            LoanStatusView.RETURNED -> copyId != null && checkedOutAt != null && dueAt != null &&
                returnedAt != null && returnedAt >= checkedOutAt && rejectedAt == null
            LoanStatusView.REJECTED -> copyId == null && checkedOutAt == null && dueAt == null &&
                returnedAt == null && rejectedAt != null && rejectedAt >= requestedAt
        }

    private fun ReservationCommandView.hasValidLifecycle(): Boolean = version >= 0 && when (status) {
        ReservationStatusView.WAITING -> copyId == null && readyAt == null && expiresAt == null &&
            fulfilledAt == null && cancelledAt == null
        ReservationStatusView.READY -> hasValidReadyState()
        ReservationStatusView.FULFILLED -> copyId != null && readyAt != null && expiresAt != null &&
            fulfilledAt != null && readyAt >= placedAt && expiresAt > readyAt &&
            fulfilledAt >= readyAt && fulfilledAt <= expiresAt && cancelledAt == null
        ReservationStatusView.CANCELLED -> fulfilledAt == null && cancelledAt != null &&
            cancelledAt >= placedAt && hasValidOpenStateShape()
        ReservationStatusView.EXPIRED -> copyId != null && readyAt != null && expiresAt != null &&
            readyAt >= placedAt && expiresAt > readyAt && fulfilledAt == null && cancelledAt == null
    }

    private fun <T> List<T>.isStrictlyOrderedBy(key: (T) -> Pair<java.time.Instant, java.util.UUID>): Boolean =
        zipWithNext().all { (left, right) ->
            val leftKey = key(left)
            val rightKey = key(right)
            leftKey.first > rightKey.first ||
                (leftKey.first == rightKey.first && leftKey.second.toString() > rightKey.second.toString())
        }

    private fun validNextCursor(cursor: String?): Boolean =
        cursor == null || CURSOR.matches(cursor)

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
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
        val REASON_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
        val CURSOR = Regex("^[A-Za-z0-9_-]{1,160}$")
        val ADMIN_LOAN_OPERATIONS = setOf("approve", "reject", "return")
        val ADMIN_LOAN_RESULTS = mapOf(
            "approve" to LoanStatusView.ACTIVE,
            "reject" to LoanStatusView.REJECTED,
            "return" to LoanStatusView.RETURNED,
        )
        val ADMIN_RESERVATION_OPERATIONS = setOf("fulfill", "expire")
        val ADMIN_RESERVATION_RESULTS = mapOf(
            "fulfill" to ReservationStatusView.FULFILLED,
            "expire" to ReservationStatusView.EXPIRED,
        )
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
