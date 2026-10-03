package com.mundiapolis.library.notification.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("app.ses-feedback")
class SesFeedbackProperties(
    val enabled: Boolean,
    val region: String,
    val queueUrl: String,
    val topicArn: String,
    val waitTime: Duration,
    val visibilityTimeout: Duration,
    val maximumMessageBytes: Int,
    val maximumMessageAge: Duration,
    val maximumFutureSkew: Duration,
    val maximumPollSilence: Duration,
    val startupGracePeriod: Duration,
    val retryBackoff: Duration,
    val maximumConsecutiveFailures: Int,
    val callTimeout: Duration,
    val attemptTimeout: Duration,
    val certificateConnectTimeout: Duration,
    val certificateReadTimeout: Duration,
    val certificateCacheDuration: Duration,
    val maximumCertificateBytes: Int,
) {
    @get:AssertTrue(message = "enabled SES feedback configuration is unsafe or incomplete")
    val isSafeConfiguration: Boolean
        get() {
            if (!enabled) return true
            val topic = TOPIC_ARN.matchEntire(topicArn) ?: return false
            val queue = runCatching { URI(queueUrl) }.getOrNull() ?: return false
            return REGION.matches(region) && topic.groupValues[1] == region &&
                topicArn != DISABLED_TOPIC_ARN && queueUrl != DISABLED_QUEUE_URL &&
                queue.scheme == "https" && queue.host == "sqs.$region.amazonaws.com" &&
                queue.userInfo == null && queue.port == -1 && queue.query == null && queue.fragment == null &&
                QUEUE_PATH.matches(queue.path) &&
                waitTime in Duration.ofSeconds(1)..Duration.ofSeconds(20) && waitTime.nano == 0 &&
                visibilityTimeout in Duration.ofSeconds(30)..Duration.ofMinutes(15) &&
                maximumMessageBytes in 1_024..262_144 &&
                maximumMessageAge in Duration.ofHours(1)..Duration.ofDays(14) &&
                maximumFutureSkew in Duration.ZERO..Duration.ofMinutes(5) &&
                maximumPollSilence in Duration.ofSeconds(30)..Duration.ofMinutes(10) &&
                startupGracePeriod in Duration.ofSeconds(10)..Duration.ofMinutes(5) &&
                retryBackoff in Duration.ofMillis(100)..Duration.ofSeconds(30) &&
                maximumConsecutiveFailures in 1..100 &&
                callTimeout in Duration.ofSeconds(2)..Duration.ofSeconds(30) &&
                attemptTimeout in Duration.ofSeconds(1)..callTimeout &&
                certificateConnectTimeout in Duration.ofMillis(250)..Duration.ofSeconds(5) &&
                certificateReadTimeout in Duration.ofMillis(500)..Duration.ofSeconds(10) &&
                certificateCacheDuration in Duration.ofMinutes(5)..Duration.ofHours(24) &&
                maximumCertificateBytes in 4_096..65_536
        }

    private companion object {
        val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z]+-[1-9][0-9]?")
        val TOPIC_ARN = Regex("arn:aws:sns:([a-z0-9-]+):[0-9]{12}:[A-Za-z0-9_-]{1,256}")
        val QUEUE_PATH = Regex("/[0-9]{12}/[A-Za-z0-9_-]{1,80}")
        const val DISABLED_TOPIC_ARN = "arn:aws:sns:eu-west-1:000000000000:disabled"
        const val DISABLED_QUEUE_URL = "https://sqs.eu-west-1.amazonaws.com/000000000000/disabled"
    }
}
