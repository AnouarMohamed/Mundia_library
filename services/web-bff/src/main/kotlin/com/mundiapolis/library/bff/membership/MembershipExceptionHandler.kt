package com.mundiapolis.library.bff.membership

import com.mundiapolis.library.bff.security.DelegatedAuthorizationProtocolException
import com.mundiapolis.library.bff.security.DelegatedAuthorizationUnavailableException
import com.mundiapolis.library.bff.security.DelegatedReauthenticationRequiredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [MembershipProfileController::class])
class MembershipExceptionHandler {
    @ExceptionHandler(
        MembershipReauthenticationRequiredException::class,
        DelegatedReauthenticationRequiredException::class,
    )
    fun reauthenticationRequired(): ProblemDetail = problem(
        HttpStatus.UNAUTHORIZED,
        "reauthentication_required",
        "Authentication must be renewed",
    )

    @ExceptionHandler(MembershipDelegationRejectedException::class)
    fun delegationRejected(): ProblemDetail = problem(
        HttpStatus.FORBIDDEN,
        "membership_access_denied",
        "Membership profile access was denied",
    )

    @ExceptionHandler(MembershipProfileNotFoundException::class)
    fun profileNotFound(): ProblemDetail = problem(
        HttpStatus.NOT_FOUND,
        "membership_profile_not_found",
        "Membership profile was not found",
    )

    @ExceptionHandler(MembershipTimeoutException::class)
    fun timeout(): ProblemDetail = problem(
        HttpStatus.GATEWAY_TIMEOUT,
        "membership_timeout",
        "Membership did not respond in time",
    )

    @ExceptionHandler(
        MembershipDelegationUnavailableException::class,
        MembershipUnavailableException::class,
        DelegatedAuthorizationUnavailableException::class,
    )
    fun unavailable(): ProblemDetail = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "membership_unavailable",
        "Membership is temporarily unavailable",
    )

    @ExceptionHandler(
        MembershipDelegationProtocolException::class,
        MembershipProtocolException::class,
        DelegatedAuthorizationProtocolException::class,
    )
    fun invalidResponse(): ProblemDetail = problem(
        HttpStatus.BAD_GATEWAY,
        "membership_invalid_response",
        "Membership returned an invalid response",
    )

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}
