package com.mundiapolis.library.notification.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("app.email-worker.membership-client")
class MembershipRecipientClientProperties(
    val enabled: Boolean,
    val baseUrl: URI,
    val tokenUri: URI,
    val clientId: String,
    val clientSecret: String,
    val audience: String,
    val allowInsecureTransport: Boolean,
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val maximumResponseBytes: Int,
) {
    @get:AssertTrue(message = "enabled membership recipient client configuration is unsafe or incomplete")
    val isSafeConfiguration: Boolean
        get() = !enabled || (isSafeEndpoint(baseUrl) && isSafeEndpoint(tokenUri) &&
            (allowInsecureTransport || (baseUrl.scheme == "https" && tokenUri.scheme == "https")) &&
            CLIENT_ID.matches(clientId) && clientId != "disabled" &&
            clientSecret.length in 16..4096 && clientSecret != "disabled-not-a-secret" &&
            clientSecret.none(Char::isISOControl) &&
            AUDIENCE.matches(audience) &&
            connectTimeout in Duration.ofMillis(100)..Duration.ofSeconds(10) &&
            readTimeout in Duration.ofMillis(100)..Duration.ofSeconds(30) &&
            maximumResponseBytes in 512..65_536)

    private fun isSafeEndpoint(uri: URI): Boolean =
        uri.isAbsolute && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.scheme in setOf("http", "https")

    private companion object {
        val CLIENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
        val AUDIENCE = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")
    }
}
