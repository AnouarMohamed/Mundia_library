package com.mundiapolis.library.digitalcontent.adapter.`in`.scan

import com.mundiapolis.library.digitalcontent.config.IngestionProperties
import com.mundiapolis.library.digitalcontent.config.MalwareScanProperties
import com.mundiapolis.library.digitalcontent.service.MalwareScanContractException
import com.mundiapolis.library.digitalcontent.service.MalwareScanEvent
import com.mundiapolis.library.digitalcontent.service.MalwareScanResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

fun interface MalwareScanMessageDecoder {
    fun decode(body: String): MalwareScanEvent
}

class GuardDutyScanDecoder(
    private val objectMapper: ObjectMapper,
    private val ingestion: IngestionProperties,
    private val scan: MalwareScanProperties,
    private val clock: Clock,
) : MalwareScanMessageDecoder {
    override fun decode(body: String): MalwareScanEvent {
        val bytes = body.toByteArray(Charsets.UTF_8)
        contract(bytes.size in 1..scan.maximumMessageBytes, "scan event size is invalid")
        val root = try {
            objectMapper.readTree(body)?.takeIf { it.isObject }
                ?: throw MalwareScanContractException("scan event is invalid")
        } catch (failure: MalwareScanContractException) {
            throw failure
        } catch (_: Exception) {
            throw MalwareScanContractException("scan event is invalid")
        }
        contract(root.requiredText("version", 8) == "0", "event version is unsupported")
        val eventId = canonicalUuid(root.requiredText("id", 64))
        contract(root.requiredText("detail-type", 80) == DETAIL_TYPE, "event type is unsupported")
        contract(root.requiredText("source", 32) == "aws.guardduty", "event source is invalid")
        contract(root.requiredText("account", 12) == ingestion.expectedBucketOwner, "event account is invalid")
        contract(root.requiredText("region", 32) == ingestion.region, "event region is invalid")
        val eventAt = root.requiredInstant("time")
        val age = Duration.between(eventAt, clock.instant())
        contract(age <= scan.maximumMessageAge && age >= scan.maximumFutureSkew.negated(), "event timestamp is invalid")
        val resources = root.get("resources")
        contract(resources != null && resources.isArray && resources.size() == 1, "event resource is invalid")
        val resource = resources[0].takeIf { it.isString }?.stringValue().orEmpty()
        val planPrefix = "arn:aws:guardduty:${ingestion.region}:${ingestion.expectedBucketOwner}:malware-protection-plan/"
        contract(resource.startsWith(planPrefix) && resource.length in (planPrefix.length + 1)..512, "event resource is invalid")

        val detail = root.requiredObject("detail")
        contract(detail.requiredText("schemaVersion", 16) == "1.0", "scan schema is unsupported")
        contract(detail.requiredText("scanStatus", 32) == "COMPLETED", "scan did not complete")
        contract(detail.requiredText("resourceType", 32) == "S3_OBJECT", "scan resource type is invalid")
        val objectDetails = detail.requiredObject("s3ObjectDetails")
        contract(objectDetails.requiredText("bucketName", 63) == ingestion.bucket, "scan bucket is invalid")
        val objectKey = objectDetails.requiredText("objectKey", 512)
        contract(OBJECT_KEY.matches(objectKey), "scan object key is invalid")
        val etag = objectDetails.requiredText("eTag", 128)
        val versionId = objectDetails.requiredText("versionId", 1024)
        contract(etag.none(Char::isISOControl) && versionId.none(Char::isISOControl), "scan object identity is invalid")
        val result = try {
            MalwareScanResult.valueOf(detail.requiredObject("scanResultDetails").requiredText("scanResultStatus", 32))
        } catch (_: IllegalArgumentException) {
            throw MalwareScanContractException("scan result is unsupported")
        }
        return MalwareScanEvent(
            eventId = eventId,
            objectKey = objectKey,
            objectVersionId = versionId,
            objectEtag = etag,
            result = result,
            eventAt = eventAt,
            payloadDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        )
    }

    private fun JsonNode.requiredObject(field: String): JsonNode = get(field)
        ?.takeIf { it.isObject }
        ?: throw MalwareScanContractException("$field is invalid")

    private fun JsonNode.requiredText(field: String, maximum: Int): String {
        val node = get(field)
        contract(node != null && node.isString, "$field is invalid")
        return node.stringValue().also {
            contract(it.length in 1..maximum && it.none(Char::isISOControl), "$field is invalid")
        }
    }

    private fun JsonNode.requiredInstant(field: String): Instant = try {
        Instant.parse(requiredText(field, 64))
    } catch (_: Exception) {
        throw MalwareScanContractException("$field is invalid")
    }

    private fun canonicalUuid(raw: String): UUID {
        val value = try {
            UUID.fromString(raw)
        } catch (_: Exception) {
            throw MalwareScanContractException("event identifier is invalid")
        }
        contract(value.toString() == raw, "event identifier is not canonical")
        return value
    }

    private fun contract(condition: Boolean, message: String) {
        if (!condition) throw MalwareScanContractException(message)
    }

    private companion object {
        const val DETAIL_TYPE = "GuardDuty Malware Protection Object Scan Result"
        val OBJECT_KEY = Regex("^quarantine/digital-content/[0-9a-f]{2}/[0-9a-f-]{36}/[0-9a-f]{64}[.](pdf|epub)$")
    }
}
