package com.mundiapolis.library.notification.adapter.`in`.web

import com.mundiapolis.library.notification.service.NotificationNotFoundException
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.net.URI

@RestControllerAdvice
class NotificationExceptionHandler {
    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
    )
    fun invalidRequest(@Suppress("UNUSED_PARAMETER") exception: Exception): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_notification_request", "Notification request parameters are invalid")

    @ExceptionHandler(NotificationNotFoundException::class)
    fun notFound(exception: NotificationNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "notification_not_found", requireNotNull(exception.message))

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            type = URI.create("urn:mundia:error:$code")
            setProperty("code", code)
        }
}
