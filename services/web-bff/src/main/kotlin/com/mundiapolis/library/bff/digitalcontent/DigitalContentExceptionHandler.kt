package com.mundiapolis.library.bff.digitalcontent

import com.mundiapolis.library.bff.security.DelegatedAuthorizationProtocolException
import com.mundiapolis.library.bff.security.DelegatedAuthorizationUnavailableException
import com.mundiapolis.library.bff.security.DelegatedReauthenticationRequiredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [DigitalContentController::class])
class DigitalContentExceptionHandler {
    @ExceptionHandler(
        DigitalContentReauthenticationRequiredException::class,
        DelegatedReauthenticationRequiredException::class,
    )
    fun reauthenticationRequired() = problem(
        HttpStatus.UNAUTHORIZED,
        "reauthentication_required",
        "Authentication must be renewed",
    )

    @ExceptionHandler(DigitalContentAccessDeniedException::class)
    fun accessDenied() = problem(
        HttpStatus.FORBIDDEN,
        "digital_content_access_denied",
        "Digital content access was denied",
    )

    @ExceptionHandler(DigitalContentInvalidRequestException::class)
    fun invalidRequest() = problem(
        HttpStatus.BAD_REQUEST,
        "invalid_digital_content_request",
        "The digital content request is invalid",
    )

    @ExceptionHandler(DigitalContentNotFoundException::class)
    fun notFound() = problem(
        HttpStatus.NOT_FOUND,
        "download_not_available",
        "The requested download is not available",
    )

    @ExceptionHandler(DigitalContentTimeoutException::class)
    fun timeout() = problem(
        HttpStatus.GATEWAY_TIMEOUT,
        "digital_content_timeout",
        "Digital Content did not respond in time",
    )

    @ExceptionHandler(
        DigitalContentUnavailableException::class,
        DelegatedAuthorizationUnavailableException::class,
    )
    fun unavailable() = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "digital_content_unavailable",
        "Digital Content is temporarily unavailable",
    )

    @ExceptionHandler(
        DigitalContentProtocolException::class,
        DelegatedAuthorizationProtocolException::class,
    )
    fun invalidResponse() = problem(
        HttpStatus.BAD_GATEWAY,
        "digital_content_invalid_response",
        "Digital Content returned an invalid response",
    )

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}
