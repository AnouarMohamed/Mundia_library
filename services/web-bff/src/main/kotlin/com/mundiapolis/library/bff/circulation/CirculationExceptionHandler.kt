package com.mundiapolis.library.bff.circulation

import com.mundiapolis.library.bff.security.DelegatedAuthorizationProtocolException
import com.mundiapolis.library.bff.security.DelegatedAuthorizationUnavailableException
import com.mundiapolis.library.bff.security.DelegatedReauthenticationRequiredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [CirculationController::class])
class CirculationExceptionHandler {
    @ExceptionHandler(
        CirculationReauthenticationRequiredException::class,
        DelegatedReauthenticationRequiredException::class,
    )
    fun reauthenticationRequired(): ProblemDetail = problem(
        HttpStatus.UNAUTHORIZED,
        "reauthentication_required",
        "Authentication must be renewed",
    )

    @ExceptionHandler(CirculationAccessDeniedException::class)
    fun accessDenied(): ProblemDetail = problem(
        HttpStatus.FORBIDDEN,
        "circulation_access_denied",
        "Circulation access was denied",
    )

    @ExceptionHandler(CirculationInvalidRequestException::class)
    fun invalidRequest(): ProblemDetail = problem(
        HttpStatus.BAD_REQUEST,
        "invalid_circulation_request",
        "The circulation request is invalid",
    )

    @ExceptionHandler(CirculationNotFoundException::class)
    fun notFound(): ProblemDetail = problem(
        HttpStatus.NOT_FOUND,
        "circulation_resource_not_found",
        "The circulation resource was not found",
    )

    @ExceptionHandler(CirculationConflictException::class)
    fun conflict(): ProblemDetail = problem(
        HttpStatus.CONFLICT,
        "circulation_conflict",
        "The circulation request conflicts with current state",
    )

    @ExceptionHandler(CirculationIneligibleException::class)
    fun ineligible(): ProblemDetail = problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "circulation_ineligible",
        "The circulation request is not currently eligible",
    )

    @ExceptionHandler(CirculationTimeoutException::class)
    fun timeout(): ProblemDetail = problem(
        HttpStatus.GATEWAY_TIMEOUT,
        "circulation_timeout",
        "Circulation did not respond in time",
    )

    @ExceptionHandler(
        CirculationUnavailableException::class,
        DelegatedAuthorizationUnavailableException::class,
    )
    fun unavailable(): ProblemDetail = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "circulation_unavailable",
        "Circulation is temporarily unavailable",
    )

    @ExceptionHandler(
        CirculationProtocolException::class,
        DelegatedAuthorizationProtocolException::class,
    )
    fun invalidResponse(): ProblemDetail = problem(
        HttpStatus.BAD_GATEWAY,
        "circulation_invalid_response",
        "Circulation returned an invalid response",
    )

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}
