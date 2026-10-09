package com.mundiapolis.library.migration.eligibility

import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

class HttpMembershipSnapshotClient(
    baseUrl: String,
    token: String,
    allowLoopbackHttp: Boolean,
    private val transport: BoundedJsonTransport,
) : MembershipSnapshotClient {
    private val origin = validateOrigin(baseUrl, allowLoopbackHttp)
    private val bearer = validateToken(token)

    override fun create(snapshotId: UUID): SnapshotReceipt = transport.exchange(
        origin.resolve("/api/v1/members/eligibility-snapshots/$snapshotId"),
        "PUT",
        bearer,
        body = null,
        SnapshotReceipt::class.java,
    )

    override fun receipt(snapshotId: UUID): SnapshotReceipt = transport.exchange(
        origin.resolve("/api/v1/members/eligibility-snapshots/$snapshotId"),
        "GET",
        bearer,
        body = null,
        SnapshotReceipt::class.java,
    )

    override fun page(snapshotId: UUID, afterMemberId: UUID?, limit: Int): SnapshotPage {
        val query = buildString {
            append("?limit=").append(limit)
            afterMemberId?.let { append("&afterMemberId=").append(encode(it.toString())) }
        }
        return transport.exchange(
            origin.resolve("/api/v1/members/eligibility-snapshots/$snapshotId/items$query"),
            "GET",
            bearer,
            body = null,
            SnapshotPage::class.java,
        )
    }
}

class HttpCirculationBootstrapClient(
    baseUrl: String,
    token: String,
    allowLoopbackHttp: Boolean,
    private val transport: BoundedJsonTransport,
) : CirculationBootstrapClient {
    private val origin = validateOrigin(baseUrl, allowLoopbackHttp)
    private val bearer = validateToken(token)

    override fun bootstrap(bootstrapId: UUID, request: BootstrapRequest): BootstrapReceipt = transport.exchange(
        origin.resolve("/api/v1/circulation/membership-eligibility-bootstrap/$bootstrapId"),
        "PUT",
        bearer,
        request,
        BootstrapReceipt::class.java,
    )

    override fun receipt(bootstrapId: UUID): BootstrapReceipt = transport.exchange(
        origin.resolve("/api/v1/circulation/membership-eligibility-bootstrap/$bootstrapId"),
        "GET",
        bearer,
        body = null,
        BootstrapReceipt::class.java,
    )
}

class BoundedJsonTransport(
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    fun <T> exchange(uri: URI, method: String, token: String, body: Any?, responseType: Class<T>): T {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
        }
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RemoteServiceException("Remote request was interrupted")
        } catch (failure: java.io.IOException) {
            throw RemoteServiceException("Remote service request failed")
        }
        response.body().use { stream ->
            if (response.statusCode() != 200) {
                throw RemoteServiceException("Remote service returned HTTP ${response.statusCode()}")
            }
            val mediaType = response.headers().firstValue("Content-Type").orElse("")
                .substringBefore(';').trim().lowercase()
            if (mediaType != "application/json") {
                throw RemoteServiceException("Remote service returned an unsupported content type")
            }
            val declared = response.headers().firstValueAsLong("Content-Length")
            if (declared.isPresent && declared.asLong > MAX_RESPONSE_BYTES) {
                throw RemoteServiceException("Remote service response exceeds the byte limit")
            }
            val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw RemoteServiceException("Remote service response exceeds the byte limit")
            }
            return runCatching { mapper.readValue(bytes, responseType) }
                .getOrElse { throw RemoteServiceException("Remote service returned invalid JSON") }
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}

private fun validateOrigin(raw: String, allowLoopbackHttp: Boolean): URI {
    val uri = runCatching { URI(raw) }.getOrNull()
        ?: throw OperatorValidationException("Service URL is invalid")
    val loopback = uri.host?.lowercase() in setOf("127.0.0.1", "localhost", "::1")
    if (
        !uri.isAbsolute || uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null ||
        (uri.path.isNotEmpty() && uri.path != "/") ||
        (uri.scheme != "https" && !(allowLoopbackHttp && uri.scheme == "http" && loopback))
    ) throw OperatorValidationException("Service URL must be an HTTPS origin")
    return URI(uri.scheme, null, uri.host, uri.port, "/", null, null)
}

private fun validateToken(token: String): String {
    if (token.length !in 16..8192 || token.any(Char::isWhitespace) || token.any(Char::isISOControl)) {
        throw OperatorValidationException("Bearer token is invalid")
    }
    return token
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
