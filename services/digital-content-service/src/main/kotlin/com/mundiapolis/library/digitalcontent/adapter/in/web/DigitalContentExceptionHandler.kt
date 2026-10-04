package com.mundiapolis.library.digitalcontent.adapter.`in`.web

import com.mundiapolis.library.digitalcontent.service.DownloadNotAvailableException
import com.mundiapolis.library.digitalcontent.service.DownloadSigningUnavailableException
import com.mundiapolis.library.digitalcontent.service.IngestionConflictException
import com.mundiapolis.library.digitalcontent.service.IngestionExpiredException
import com.mundiapolis.library.digitalcontent.service.IngestionUnavailableException
import com.mundiapolis.library.digitalcontent.service.InvalidIngestionRequestException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [DigitalContentController::class])
class DigitalContentExceptionHandler {
    @ExceptionHandler(DownloadNotAvailableException::class)
    fun unavailableAsset(): ProblemDetail = problem(
        HttpStatus.NOT_FOUND,
        "download_not_available",
        "The requested download is not available",
    )

    @ExceptionHandler(DownloadSigningUnavailableException::class)
    fun signingUnavailable(): ProblemDetail = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "download_signing_unavailable",
        "Download authorization is temporarily unavailable",
    )

    @ExceptionHandler(IngestionConflictException::class)
    fun ingestionConflict(): ProblemDetail = problem(
        HttpStatus.CONFLICT,
        "ingestion_id_conflict",
        "The ingestion identifier is already bound to another command",
    )

    @ExceptionHandler(IngestionExpiredException::class)
    fun ingestionExpired(): ProblemDetail = problem(
        HttpStatus.GONE,
        "ingestion_expired",
        "The ingestion session has expired",
    )

    @ExceptionHandler(IngestionUnavailableException::class)
    fun ingestionUnavailable(): ProblemDetail = problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "ingestion_unavailable",
        "Quarantine upload authorization is unavailable",
    )

    @ExceptionHandler(InvalidIngestionRequestException::class)
    fun invalidIngestion(): ProblemDetail = problem(
        HttpStatus.BAD_REQUEST,
        "invalid_ingestion_request",
        "The ingestion manifest is invalid",
    )

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}
