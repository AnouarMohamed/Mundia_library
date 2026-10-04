package com.mundiapolis.library.bff.notification

import com.mundiapolis.library.bff.security.DelegatedAuthorizationProtocolException
import com.mundiapolis.library.bff.security.DelegatedAuthorizationUnavailableException
import com.mundiapolis.library.bff.security.DelegatedReauthenticationRequiredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [NotificationController::class])
class NotificationExceptionHandler {
    @ExceptionHandler(
        NotificationReauthenticationRequiredException::class,
        DelegatedReauthenticationRequiredException::class,
    )
    fun reauthenticationRequired() = problem(
        HttpStatus.UNAUTHORIZED,
        "reauthentication_required",
        "Authentication must be renewed",
    )

    @ExceptionHandler(NotificationAccessDeniedException::class)
    fun accessDenied() = problem(
        HttpStatus.FORBIDDEN,
        "notification_access_denied",
        "Notification access was denied",
    )

    @ExceptionHandler(NotificationInvalidRequestException::class)
    fun invalidRequest() = problem(
        HttpStatus.BAD_REQUEST,
        "invalid_notification_request",
        "The notification request is invalid",
    )

    @ExceptionHandler(NotificationNotFoundException::class)
    fun notFound() = problem(HttpStatus.NOT_FOUND, "notification_not_found", "The notification was not found")

    @ExceptionHandler(NotificationConflictException::class)
    fun conflict() = problem(
        HttpStatus.CONFLICT,
        "notification_conflict",
        "The notification request conflicts with current state",
    )

    @ExceptionHandler(NotificationPreconditionRequiredException::class)
    fun preconditionRequired() = problem(
        HttpStatus.PRECONDITION_REQUIRED,
        "notification_precondition_required",
        "A current preference version is required",
    )

    @ExceptionHandler(NotificationTimeoutException::class)
    fun timeout() = problem(
        HttpStatus.GATEWAY_TIMEOUT,
        "notification_timeout",
        "Notification did not respond in time",
    )

    @ExceptionHandler(
        NotificationUnavailableException::class,
        DelegatedAuthorizationUnavailableException::class,
    )
    fun unavailable() = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "notification_unavailable",
        "Notification is temporarily unavailable",
    )

    @ExceptionHandler(
        NotificationProtocolException::class,
        DelegatedAuthorizationProtocolException::class,
    )
    fun invalidResponse() = problem(
        HttpStatus.BAD_GATEWAY,
        "notification_invalid_response",
        "Notification returned an invalid response",
    )

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}
