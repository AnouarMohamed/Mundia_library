package com.mundiapolis.library.circulation.adapter.`in`.web

import com.mundiapolis.library.circulation.application.port.outbound.RateLimitStore
import com.mundiapolis.library.circulation.config.RateLimitProperties
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

@Component
class AuthenticatedRateLimitFilter(
    private val rateLimitStore: RateLimitStore,
    private val properties: RateLimitProperties,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.enabled ||
            request.requestURI.startsWith("/actuator/health") ||
            request.requestURI == "/openapi/circulation-v1.json"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication !is JwtAuthenticationToken || !authentication.isAuthenticated) {
            filterChain.doFilter(request, response)
            return
        }
        val budget = classify(request)
        val now = Instant.now()
        val decision = try {
            rateLimitStore.consume(
                principalFingerprint(authentication),
                budget.key,
                budget.limit,
                properties.window,
                now,
            )
        } catch (_: RuntimeException) {
            meterRegistry.counter(
                "mundia.rate_limit.requests",
                "bucket",
                budget.key,
                "outcome",
                "unavailable",
            ).increment()
            writeProblem(
                response,
                status = HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                code = "rate_limit_unavailable",
                detail = "Request admission is temporarily unavailable",
            )
            return
        }

        response.setHeader("RateLimit-Limit", decision.limit.toString())
        response.setHeader("RateLimit-Remaining", decision.remaining.toString())
        val resetAfterSeconds = Duration.between(now, decision.resetsAt)
            .toMillis()
            .coerceAtLeast(1)
            .let { (it + 999) / 1000 }
        response.setHeader(
            "RateLimit-Reset",
            resetAfterSeconds.toString(),
        )
        if (!decision.allowed) {
            meterRegistry.counter(
                "mundia.rate_limit.requests",
                "bucket",
                budget.key,
                "outcome",
                "rejected",
            ).increment()
            response.setHeader("Retry-After", resetAfterSeconds.toString())
            writeProblem(
                response,
                status = 429,
                code = "rate_limit_exceeded",
                detail = "Too many requests; retry after the current window resets",
            )
            return
        }
        meterRegistry.counter(
            "mundia.rate_limit.requests",
            "bucket",
            budget.key,
            "outcome",
            "allowed",
        ).increment()
        filterChain.doFilter(request, response)
    }

    private fun classify(request: HttpServletRequest): Budget {
        val path = request.requestURI
        if (
            request.method == "PUT" ||
            SENSITIVE_PATHS.any(path::contains)
        ) {
            return Budget("sensitive", properties.sensitiveRequests)
        }
        if (request.method != "GET" && request.method != "HEAD") {
            return Budget("command", properties.commandRequests)
        }
        return Budget("read", properties.readRequests)
    }

    private fun principalFingerprint(authentication: JwtAuthenticationToken): String {
        val jwt = authentication.token
        val identityParts = listOf(
            jwt.issuer?.toString() ?: "",
            jwt.subject ?: "",
            jwt.claims["azp"] as? String ?: "",
            jwt.claims["client_id"] as? String ?: "",
        )
        val canonical = buildString {
            identityParts.forEach { part ->
                append(part.length)
                append(':')
                append(part)
            }
        }
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun writeProblem(
        response: HttpServletResponse,
        status: Int,
        code: String,
        detail: String,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.setHeader("Cache-Control", "no-store")
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "type" to "urn:mundia:error:$code",
                    "title" to if (status == 429) "Too Many Requests" else "Service Unavailable",
                    "status" to status,
                    "detail" to detail,
                    "code" to code,
                ),
            ),
        )
    }

    private data class Budget(val key: String, val limit: Int)

    private companion object {
        val SENSITIVE_PATHS = setOf(
            "/approve",
            "/reject",
            "/return",
            "/fulfill",
            "/expire",
            "/fines",
            "/inventory",
            "/policy",
        )
    }
}
