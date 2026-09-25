package com.mundiapolis.library.membership.adapter.`in`.web

import com.mundiapolis.library.membership.dto.InvalidMembershipActorException
import com.mundiapolis.library.membership.dto.InvalidMembershipCommandException
import com.mundiapolis.library.membership.dto.MembershipCommandConflictException
import com.mundiapolis.library.membership.dto.MembershipCommandNotFoundException
import com.mundiapolis.library.membership.dto.MembershipIdempotencyConflictException
import com.mundiapolis.library.membership.dto.MembershipIdempotencyIncompleteException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class MembershipExceptionHandler {
    @ExceptionHandler(InvalidMembershipCommandException::class, IllegalArgumentException::class)
    fun invalidCommand(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_membership_command", exception.message)

    @ExceptionHandler(InvalidMembershipActorException::class)
    fun invalidActor(exception: InvalidMembershipActorException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "invalid_membership_actor", exception.message)

    @ExceptionHandler(MembershipCommandNotFoundException::class)
    fun notFound(exception: MembershipCommandNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "membership_command_target_not_found", exception.message)

    @ExceptionHandler(
        MembershipCommandConflictException::class,
        MembershipIdempotencyConflictException::class,
        MembershipIdempotencyIncompleteException::class,
    )
    fun conflict(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "membership_command_conflict", exception.message)

    private fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, requireNotNull(detail)).apply {
            title = status.reasonPhrase
            type = URI.create("urn:mundia:error:$code")
            setProperty("code", code)
        }
}
