package com.mundiapolis.library.notification.adapter.`in`.web

import com.mundiapolis.library.notification.service.NotificationNotFoundException
import com.mundiapolis.library.notification.service.EmailSuppressionRemovalConflictException
import com.mundiapolis.library.notification.service.NotificationPreferencePreconditionRequiredException
import com.mundiapolis.library.notification.service.NotificationPreferenceVersionConflictException
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

    @ExceptionHandler(NotificationPreferencePreconditionRequiredException::class)
    fun preconditionRequired(exception: NotificationPreferencePreconditionRequiredException): ProblemDetail =
        problem(HttpStatus.PRECONDITION_REQUIRED, "preference_precondition_required", requireNotNull(exception.message))

    @ExceptionHandler(NotificationPreferenceVersionConflictException::class)
    fun versionConflict(exception: NotificationPreferenceVersionConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "preference_version_conflict", requireNotNull(exception.message))

    @ExceptionHandler(EmailSuppressionRemovalConflictException::class)
    fun suppressionRemovalConflict(exception: EmailSuppressionRemovalConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "suppression_removal_idempotency_conflict", requireNotNull(exception.message))

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            type = URI.create("urn:mundia:error:$code")
            setProperty("code", code)
        }
}
