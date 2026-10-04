package com.mundiapolis.library.digitalcontent.service

import java.time.Instant

data class SignedDownload(
    val url: String,
    val expiresAt: Instant,
)

fun interface DownloadUrlSigner {
    fun sign(objectKey: String, issuedAt: Instant): SignedDownload
}

class DownloadSigningUnavailableException(cause: Throwable? = null) : RuntimeException(cause)
