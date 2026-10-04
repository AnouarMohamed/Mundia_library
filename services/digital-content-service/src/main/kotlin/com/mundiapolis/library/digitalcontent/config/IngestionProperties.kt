package com.mundiapolis.library.digitalcontent.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("app.ingestion")
class IngestionProperties(
    val enabled: Boolean,
    val region: String,
    val bucket: String,
    val expectedBucketOwner: String,
    val kmsKeyId: String,
    val uploadUrlLifetime: Duration,
    val sessionLifetime: Duration,
    val callTimeout: Duration,
    val attemptTimeout: Duration,
) {
    @get:AssertTrue(message = "enabled ingestion configuration is unsafe or incomplete")
    val isSafeConfiguration: Boolean
        get() {
            if (!enabled) return true
            return REGION.matches(region) && BUCKET.matches(bucket) && bucket != DISABLED_BUCKET &&
                ACCOUNT.matches(expectedBucketOwner) && expectedBucketOwner != DISABLED_ACCOUNT &&
                KMS_KEY_ARN.matches(kmsKeyId) &&
                kmsKeyId.startsWith("arn:aws:kms:$region:$expectedBucketOwner:key/") &&
                uploadUrlLifetime in Duration.ofMinutes(1)..Duration.ofMinutes(15) &&
                sessionLifetime in Duration.ofHours(1)..Duration.ofDays(7) &&
                callTimeout in Duration.ofSeconds(2)..Duration.ofSeconds(30) &&
                attemptTimeout in Duration.ofSeconds(1)..callTimeout
        }

    private companion object {
        val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z]+-[1-9][0-9]?")
        val BUCKET = Regex("(?=.{3,63}$)(?![0-9]+(?:[.][0-9]+){3}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
        val ACCOUNT = Regex("[0-9]{12}")
        val KMS_KEY_ARN = Regex("arn:aws:kms:[a-z0-9-]+:[0-9]{12}:key/(?:[0-9a-f-]{36}|mrk-[0-9a-f]{32})")
        const val DISABLED_BUCKET = "disabled-quarantine-bucket"
        const val DISABLED_ACCOUNT = "000000000000"
    }
}
