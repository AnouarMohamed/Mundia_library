package com.mundiapolis.library.bff.catalog

import com.mundiapolis.library.bff.security.DelegatedAuthorizationProtocolException
import com.mundiapolis.library.bff.security.DelegatedAuthorizationUnavailableException
import com.mundiapolis.library.bff.security.DelegatedReauthenticationRequiredException
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@RestControllerAdvice(assignableTypes = [CatalogBrowseController::class, CatalogAdminController::class])
class CatalogExceptionHandler {
    @ExceptionHandler(CatalogReauthenticationRequiredException::class, DelegatedReauthenticationRequiredException::class)
    fun reauthenticationRequired() = problem(HttpStatus.UNAUTHORIZED, "reauthentication_required", "Authentication must be renewed")

    @ExceptionHandler(CatalogAccessDeniedException::class)
    fun accessDenied() = problem(HttpStatus.FORBIDDEN, "catalog_access_denied", "Catalog access was denied")

    @ExceptionHandler(CatalogNotFoundException::class)
    fun notFound() = problem(HttpStatus.NOT_FOUND, "catalog_resource_not_found", "The requested catalog resource was not found")

    @ExceptionHandler(CatalogConflictException::class)
    fun conflict() = problem(HttpStatus.CONFLICT, "catalog_conflict", "The catalog record changed or the command conflicts with existing data")

    @ExceptionHandler(CatalogInvalidRequestException::class)
    fun downstreamInvalidRequest() = invalidRequest()

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

    @ExceptionHandler(CatalogUnavailableException::class, DelegatedAuthorizationUnavailableException::class)
    fun unavailable(): ProblemDetail = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "catalog_unavailable",
        "Catalog is temporarily unavailable",
    )

    @ExceptionHandler(CatalogProtocolException::class, DelegatedAuthorizationProtocolException::class)
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
