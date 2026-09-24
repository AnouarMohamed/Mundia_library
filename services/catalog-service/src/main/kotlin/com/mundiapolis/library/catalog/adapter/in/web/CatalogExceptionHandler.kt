package com.mundiapolis.library.catalog.adapter.`in`.web

import com.mundiapolis.library.catalog.dto.CatalogCommandConflictException
import com.mundiapolis.library.catalog.dto.CatalogCommandNotFoundException
import com.mundiapolis.library.catalog.dto.CatalogIdempotencyConflictException
import com.mundiapolis.library.catalog.dto.CatalogIdempotencyIncompleteException
import com.mundiapolis.library.catalog.dto.InvalidCatalogActorException
import com.mundiapolis.library.catalog.dto.InvalidCatalogCommandException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class CatalogExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class, ArithmeticException::class)
    fun invalidRequest(exception: RuntimeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            exception.message ?: "Invalid catalog request",
        ).also { it.title = "Invalid catalog request" }

    @ExceptionHandler(InvalidCatalogCommandException::class)
    fun invalidCommand(exception: InvalidCatalogCommandException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_catalog_command", exception.message)

    @ExceptionHandler(InvalidCatalogActorException::class)
    fun invalidActor(exception: InvalidCatalogActorException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "invalid_catalog_actor", exception.message)

    @ExceptionHandler(CatalogCommandNotFoundException::class)
    fun commandTargetNotFound(exception: CatalogCommandNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "catalog_command_target_not_found", exception.message)

    @ExceptionHandler(
        CatalogCommandConflictException::class,
        CatalogIdempotencyConflictException::class,
        CatalogIdempotencyIncompleteException::class,
    )
    fun commandConflict(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "catalog_command_conflict", exception.message)

    private fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, requireNotNull(detail)).apply {
            title = status.reasonPhrase
            type = URI.create("urn:mundia:error:$code")
            setProperty("code", code)
        }
}
