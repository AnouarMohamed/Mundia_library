package com.mundiapolis.library.notification.adapter.`in`.web

import com.mundiapolis.library.notification.service.NotificationNotFoundException
import com.mundiapolis.library.notification.service.EmailSuppressionRemovalConflictException
import com.mundiapolis.library.notification.service.DeadLetterDeliveryNotFoundException
import com.mundiapolis.library.notification.service.DeadLetterDeliveryStateConflictException
import com.mundiapolis.library.notification.service.DeadLetterRecipientSuppressedException
import com.mundiapolis.library.notification.service.DeadLetterReplayIdempotencyConflictException
import com.mundiapolis.library.notification.service.DeadLetterReplayUnsafeFailureException
import com.mundiapolis.library.notification.service.DeadLetterReplayLimitExceededException
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

    @ExceptionHandler(DeadLetterDeliveryNotFoundException::class)
    fun deadLetterNotFound(exception: DeadLetterDeliveryNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "email_delivery_not_found", requireNotNull(exception.message))

    @ExceptionHandler(
        DeadLetterDeliveryStateConflictException::class,
        DeadLetterRecipientSuppressedException::class,
        DeadLetterReplayIdempotencyConflictException::class,
        DeadLetterReplayUnsafeFailureException::class,
        DeadLetterReplayLimitExceededException::class,
    )
    fun deadLetterReplayConflict(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "dead_letter_replay_conflict", requireNotNull(exception.message))

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            type = URI.create("urn:mundia:error:$code")
            setProperty("code", code)
        }
}
