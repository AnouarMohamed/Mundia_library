package com.mundiapolis.library.catalog.adapter.`in`.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class CatalogExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class, ArithmeticException::class)
    fun invalidRequest(exception: RuntimeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            exception.message ?: "Invalid catalog request",
        ).also { it.title = "Invalid catalog request" }
}
