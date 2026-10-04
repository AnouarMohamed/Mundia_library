package com.mundiapolis.library.digitalcontent.adapter.`in`.web

import com.mundiapolis.library.digitalcontent.service.DownloadNotAvailableException
import com.mundiapolis.library.digitalcontent.service.DownloadSigningUnavailableException
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

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}
