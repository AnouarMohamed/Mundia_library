package com.mundiapolis.library.catalog.adapter.outbound.events

import com.google.protobuf.Timestamp
import com.mundiapolis.library.catalog.config.CatalogOutboxProperties
import com.mundiapolis.library.catalog.contract.v1.CatalogEvent
import com.mundiapolis.library.catalog.contract.v1.Contributor
import com.mundiapolis.library.catalog.contract.v1.EditionEvent
import com.mundiapolis.library.catalog.contract.v1.WorkEvent
import com.mundiapolis.library.catalog.dto.CatalogOutboxContractException
import com.mundiapolis.library.catalog.dto.CatalogOutboxPayloadTooLargeException
import com.mundiapolis.library.catalog.dto.ClaimedCatalogOutboxEvent
import com.mundiapolis.library.catalog.dto.EncodedCatalogEvent
import com.mundiapolis.library.catalog.service.CatalogEventEncoder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.net.URI
import java.util.UUID

class ProtobufCatalogEventEncoder(
    private val objectMapper: ObjectMapper,
    private val properties: CatalogOutboxProperties,
) : CatalogEventEncoder {
    override fun encode(event: ClaimedCatalogOutboxEvent): EncodedCatalogEvent {
        requireContract(event.eventVersion == CONTRACT_VERSION)
        requireContract(event.aggregateVersion >= 0)
        requireContract(EVENT_TYPES[event.eventType] == event.aggregateType)
        val payload = parsePayload(event.payloadJson)
        val envelope = CatalogEvent.newBuilder()
            .setEventId(event.eventId.toString())
            .setEventType(event.eventType)
            .setEventVersion(event.eventVersion)
            .setAggregateType(event.aggregateType)
            .setAggregateId(event.aggregateId.toString())
            .setAggregateVersion(event.aggregateVersion)
            .setOccurredAt(event.occurredAt.toTimestamp())
            .apply {
                when (event.aggregateType) {
                    WORK_AGGREGATE -> setWork(encodeWork(event, payload))
                    EDITION_AGGREGATE -> setEdition(encodeEdition(event, payload))
                    else -> throw CatalogOutboxContractException("Unknown Catalog aggregate type")
                }
            }
            .build()
        val bytes = envelope.toByteArray()
        if (bytes.size > properties.maximumEventBytes) {
            throw CatalogOutboxPayloadTooLargeException()
        }
        return EncodedCatalogEvent(
            eventId = event.eventId,
            key = event.aggregateId.toString(),
            eventType = event.eventType,
            eventVersion = event.eventVersion,
            schemaSubject = properties.schemaSubject,
            schemaVersion = CONTRACT_VERSION,
            payload = bytes,
        )
    }

    private fun encodeWork(event: ClaimedCatalogOutboxEvent, payload: JsonNode): WorkEvent {
        requireContract(payload.isObject && payload.propertyNames().all(WORK_FIELDS::contains))
        val workId = payload.requiredUuid("workId")
        requireContract(workId == event.aggregateId)
        val rating = payload.requiredDouble("rating")
        requireContract(rating in 0.0..5.0)
        val authorsNode = payload["authors"]
        requireContract(authorsNode != null && authorsNode.isArray && authorsNode.size() in 1..20)
        val authors = (0 until authorsNode.size()).map { index ->
            val author = authorsNode[index]
            requireContract(author.isObject && author.propertyNames().all(AUTHOR_FIELDS::contains))
            Contributor.newBuilder()
                .setContributorId(author.requiredUuid("contributorId").toString())
                .setName(author.requiredText("name", 300))
                .apply { author.optionalText("bio", 5_000)?.let(::setBiography) }
                .build()
        }
        return WorkEvent.newBuilder()
            .setWorkId(workId.toString())
            .setTitle(payload.requiredText("title", 500))
            .setSummary(payload.requiredText("summary", 1_000, allowEmpty = true))
            .setDescription(payload.requiredText("description", 10_000, allowEmpty = true))
            .setGenre(payload.requiredText("genre", 120))
            .setRating(rating)
            .addAllAuthors(authors)
            .build()
    }

    private fun encodeEdition(event: ClaimedCatalogOutboxEvent, payload: JsonNode): EditionEvent {
        requireContract(payload.isObject && payload.propertyNames().all(EDITION_FIELDS::contains))
        val editionId = payload.requiredUuid("editionId")
        requireContract(editionId == event.aggregateId)
        val publicationYear = payload.requiredInt("publicationYear")
        val pageCount = payload.requiredInt("pageCount")
        requireContract(publicationYear in 1000..3000 && pageCount in 1..100_000)
        return EditionEvent.newBuilder()
            .setEditionId(editionId.toString())
            .setWorkId(payload.requiredUuid("workId").toString())
            .setTitle(payload.requiredText("title", 500))
            .setIsbn(payload.requiredText("isbn", 32))
            .setPublisher(payload.requiredText("publisher", 300))
            .setPublicationYear(publicationYear)
            .setLanguage(payload.requiredText("language", 80))
            .setPageCount(pageCount)
            .setIsActive(payload.requiredBoolean("isActive"))
            .apply {
                payload.optionalHttpsUrl("coverUrl")?.let(::setCoverUrl)
                payload.optionalText("coverColor", 7)?.let { color ->
                    requireContract(COVER_COLOR.matches(color))
                    setCoverColor(color)
                }
                payload.optionalHttpsUrl("videoUrl")?.let(::setVideoUrl)
            }
            .build()
    }

    private fun parsePayload(raw: String): JsonNode = try {
        objectMapper.readTree(raw) ?: throw CatalogOutboxContractException("Missing Catalog event payload")
    } catch (exception: CatalogOutboxContractException) {
        throw exception
    } catch (_: Exception) {
        throw CatalogOutboxContractException("Invalid Catalog event payload")
    }

    private fun JsonNode.requiredText(field: String, maximum: Int, allowEmpty: Boolean = false): String {
        val value = get(field)
        requireContract(value != null && value.isString)
        val text = value.stringValue()
        requireContract(text == text.trim() && text.length <= maximum && text.none(Char::isISOControl))
        requireContract(allowEmpty || text.isNotEmpty())
        return text
    }

    private fun JsonNode.optionalText(field: String, maximum: Int): String? {
        val value = get(field) ?: return null
        if (value.isNull) return null
        requireContract(value.isString)
        val text = value.stringValue()
        requireContract(text.isNotEmpty() && text == text.trim() && text.length <= maximum)
        requireContract(text.none(Char::isISOControl))
        return text
    }

    private fun JsonNode.optionalHttpsUrl(field: String): String? {
        val text = optionalText(field, 2_048) ?: return null
        val uri = runCatching { URI(text) }.getOrNull()
        requireContract(
            uri != null &&
                uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        )
        return text
    }

    private fun JsonNode.requiredUuid(field: String): UUID {
        val raw = requiredText(field, 36)
        val value = runCatching { UUID.fromString(raw) }.getOrNull()
        requireContract(value != null && value.toString() == raw.lowercase())
        return requireNotNull(value)
    }

    private fun JsonNode.requiredDouble(field: String): Double {
        val value = get(field)
        requireContract(value != null && value.isNumber)
        return value.doubleValue().also { requireContract(it.isFinite()) }
    }

    private fun JsonNode.requiredInt(field: String): Int {
        val value = get(field)
        requireContract(value != null && value.isIntegralNumber && value.canConvertToInt())
        return value.intValue()
    }

    private fun JsonNode.requiredBoolean(field: String): Boolean {
        val value = get(field)
        requireContract(value != null && value.isBoolean)
        return value.booleanValue()
    }

    private fun Instant.toTimestamp(): Timestamp = Timestamp.newBuilder()
        .setSeconds(epochSecond)
        .setNanos(nano)
        .build()

    private fun requireContract(condition: Boolean) {
        if (!condition) throw CatalogOutboxContractException("Invalid Catalog event contract")
    }

    private companion object {
        const val CONTRACT_VERSION = 1
        const val WORK_AGGREGATE = "work"
        const val EDITION_AGGREGATE = "edition"
        val COVER_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        val EVENT_TYPES = mapOf(
            "catalog.work.created" to WORK_AGGREGATE,
            "catalog.work.updated" to WORK_AGGREGATE,
            "catalog.edition.created" to EDITION_AGGREGATE,
            "catalog.edition.updated" to EDITION_AGGREGATE,
            "catalog.edition.activation-changed" to EDITION_AGGREGATE,
        )
        val WORK_FIELDS = setOf("workId", "title", "summary", "description", "genre", "rating", "authors")
        val AUTHOR_FIELDS = setOf("contributorId", "name", "bio")
        val EDITION_FIELDS = setOf(
            "editionId",
            "workId",
            "title",
            "isbn",
            "publisher",
            "publicationYear",
            "language",
            "pageCount",
            "coverUrl",
            "coverColor",
            "videoUrl",
            "isActive",
        )
    }
}
