package com.mundiapolis.library.digitalcontent

import com.mundiapolis.library.digitalcontent.adapter.`in`.scan.GuardDutyScanDecoder
import com.mundiapolis.library.digitalcontent.config.IngestionProperties
import com.mundiapolis.library.digitalcontent.config.MalwareScanProperties
import com.mundiapolis.library.digitalcontent.service.MalwareScanContractException
import com.mundiapolis.library.digitalcontent.service.MalwareScanResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class GuardDutyScanDecoderTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val decoder = GuardDutyScanDecoder(
        JsonMapper.builder().findAndAddModules().build(),
        ingestionProperties(),
        scanProperties(),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `decodes exact clean GuardDuty result and preserves immutable object identity`() {
        val result = decoder.decode(event())
        assertEquals(EVENT_ID, result.eventId.toString())
        assertEquals(OBJECT_KEY, result.objectKey)
        assertEquals("version-1", result.objectVersionId)
        assertEquals(MalwareScanResult.NO_THREATS_FOUND, result.result)
        assertEquals(64, result.payloadDigest.length)
    }

    @Test
    fun `rejects wrong trust boundary incomplete scans and unknown results`() {
        assertThrows(MalwareScanContractException::class.java) {
            decoder.decode(event().replace("111122223333", "999988887777"))
        }
        assertThrows(MalwareScanContractException::class.java) {
            decoder.decode(event().replace("\"COMPLETED\"", "\"SKIPPED\""))
        }
        assertThrows(MalwareScanContractException::class.java) {
            decoder.decode(event().replace("\"NO_THREATS_FOUND\"", "\"NEW_STATUS\""))
        }
    }

    private fun event() = """
        {
          "version":"0",
          "id":"$EVENT_ID",
          "detail-type":"GuardDuty Malware Protection Object Scan Result",
          "source":"aws.guardduty",
          "account":"111122223333",
          "time":"2026-10-04T11:59:00Z",
          "region":"eu-west-1",
          "resources":["arn:aws:guardduty:eu-west-1:111122223333:malware-protection-plan/plan-1"],
          "detail":{
            "schemaVersion":"1.0",
            "scanStatus":"COMPLETED",
            "resourceType":"S3_OBJECT",
            "s3ObjectDetails":{
              "bucketName":"mundia-quarantine-prod",
              "objectKey":"$OBJECT_KEY",
              "eTag":"etag-1",
              "versionId":"version-1",
              "s3Throttled":false
            },
            "scanResultDetails":{"scanResultStatus":"NO_THREATS_FOUND","threats":null,"statusReasons":null}
          }
        }
    """.trimIndent()

    private fun ingestionProperties() = IngestionProperties(
        enabled = true,
        region = "eu-west-1",
        bucket = "mundia-quarantine-prod",
        expectedBucketOwner = "111122223333",
        kmsKeyId = "arn:aws:kms:eu-west-1:111122223333:key/00000000-0000-0000-0000-000000000001",
        uploadUrlLifetime = Duration.ofMinutes(5),
        sessionLifetime = Duration.ofHours(24),
        callTimeout = Duration.ofSeconds(10),
        attemptTimeout = Duration.ofSeconds(5),
    )

    private fun scanProperties() = MalwareScanProperties(
        enabled = true,
        queueUrl = "https://sqs.eu-west-1.amazonaws.com/111122223333/scan-events",
        waitTime = Duration.ofSeconds(10),
        visibilityTimeout = Duration.ofMinutes(2),
        maximumMessageBytes = 262_144,
        maximumMessageAge = Duration.ofDays(14),
        maximumFutureSkew = Duration.ofMinutes(5),
        maximumPollSilence = Duration.ofMinutes(2),
        startupGracePeriod = Duration.ofMinutes(1),
        retryBackoff = Duration.ofSeconds(1),
        maximumConsecutiveFailures = 5,
    )

    private companion object {
        const val EVENT_ID = "14000000-0000-0000-0000-000000000001"
        const val OBJECT_KEY = "quarantine/digital-content/aa/13000000-0000-0000-0000-000000000001/" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.pdf"
    }
}
