package com.mundiapolis.library.bff.catalog

import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@RestControllerAdvice(assignableTypes = [CatalogBrowseController::class])
class CatalogExceptionHandler {
    @ExceptionHandler(HandlerMethodValidationException::class, ConstraintViolationException::class)
    fun invalidRequest(): ProblemDetail = problem(
        HttpStatus.BAD_REQUEST,
        "invalid_catalog_search",
        "Catalog search parameters are invalid",
    )

    @ExceptionHandler(CatalogTimeoutException::class)
    fun timeout(): ProblemDetail = problem(
        HttpStatus.GATEWAY_TIMEOUT,
        "catalog_timeout",
        "Catalog did not respond in time",
    )

    @ExceptionHandler(CatalogUnavailableException::class)
    fun unavailable(): ProblemDetail = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "catalog_unavailable",
        "Catalog is temporarily unavailable",
    )

    @ExceptionHandler(CatalogProtocolException::class)
    fun invalidResponse(): ProblemDetail = problem(
        HttpStatus.BAD_GATEWAY,
        "catalog_invalid_response",
        "Catalog returned an invalid response",
    )

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}
