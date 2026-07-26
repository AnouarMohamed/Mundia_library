package com.mundiapolis.library.circulation.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class CommandRequestBodyLimitFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in BODY_METHODS ||
            !request.requestURI.startsWith(CIRCULATION_API_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > MAX_COMMAND_BODY_BYTES) {
            writePayloadTooLarge(response)
            return
        }

        val body = request.inputStream.readNBytes(MAX_COMMAND_BODY_BYTES + 1)
        if (body.size > MAX_COMMAND_BODY_BYTES) {
            writePayloadTooLarge(response)
            return
        }

        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun writePayloadTooLarge(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """{"type":"urn:mundia:error:payload_too_large","title":"Payload Too Large","status":413,"detail":"Command request body exceeds 16384 bytes","code":"payload_too_large"}""",
        )
    }

    private class CachedBodyRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
    }

    private class ByteArrayServletInputStream(
        body: ByteArray,
    ) : ServletInputStream() {
        private val delegate = ByteArrayInputStream(body)

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun isFinished(): Boolean = delegate.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener) {
            if (isFinished) {
                listener.onAllDataRead()
            } else {
                listener.onDataAvailable()
            }
        }
    }

    private companion object {
        const val CIRCULATION_API_PREFIX = "/api/v1/circulation/"
        const val MAX_COMMAND_BODY_BYTES = 16 * 1024
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
