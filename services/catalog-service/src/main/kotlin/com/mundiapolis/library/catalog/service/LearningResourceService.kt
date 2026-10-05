package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.adapter.outbound.persistence.JooqLearningResourceRepository
import com.mundiapolis.library.catalog.dto.InvalidCatalogActorException
import com.mundiapolis.library.catalog.dto.InvalidCatalogCommandException
import com.mundiapolis.library.catalog.dto.LearningResource
import com.mundiapolis.library.catalog.dto.LearningResourceImportCommand
import com.mundiapolis.library.catalog.dto.LearningResourceImportItem
import com.mundiapolis.library.catalog.dto.LearningResourceImportResult
import com.mundiapolis.library.catalog.dto.LearningResourcePage
import org.springframework.stereotype.Service
import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class LearningResourceService(
    private val repository: JooqLearningResourceRepository,
    private val clock: Clock,
) {
    fun importBatch(command: LearningResourceImportCommand): LearningResourceImportResult {
        if (!SHA256.matches(command.actorFingerprint)) {
            throw InvalidCatalogActorException("Catalog import actor fingerprint is invalid")
        }
        val sourceName = command.sourceName.safeText("sourceName", 100, 2)
        val sourceRevision = command.sourceRevision.safeText("sourceRevision", 128)
        if (command.items.isEmpty() || command.items.size > MAX_BATCH_SIZE) {
            throw InvalidCatalogCommandException("items must contain between 1 and $MAX_BATCH_SIZE records")
        }
        if (command.items.map { it.resourceId }.toSet().size != command.items.size) {
            throw InvalidCatalogCommandException("items must not contain duplicate resource IDs")
        }
        if (command.items.map { it.sourceRecordKey }.toSet().size != command.items.size) {
            throw InvalidCatalogCommandException("items must not contain duplicate source record keys")
        }
        val items = command.items.map(::normalizeItem)
        val normalized = command.copy(sourceName = sourceName, sourceRevision = sourceRevision, items = items)
        val manifest = sha256(canonicalManifest(normalized))
        return repository.importBatch(
            normalized,
            manifest,
            clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }

    fun importEvidence(importId: UUID): LearningResourceImportResult = repository.importEvidence(importId)

    fun find(resourceId: UUID): LearningResource? = repository.find(resourceId)

    fun search(query: String?, category: String?, page: Int?, limit: Int?): LearningResourcePage {
        val safeQuery = query?.trim()?.takeIf(String::isNotEmpty)?.safeText("query", 200)
        val safeCategory = category?.trim()?.takeIf(String::isNotEmpty)?.safeText("category", 128)
        val safePage = page ?: 0
        val safeLimit = limit ?: 24
        if (safePage !in 0..10_000) throw InvalidCatalogCommandException("page must be between 0 and 10000")
        if (safeLimit !in 1..100) throw InvalidCatalogCommandException("limit must be between 1 and 100")
        return repository.search(safeQuery, safeCategory, safePage, safeLimit)
    }

    fun categories(): List<String> = repository.categories()

    private fun normalizeItem(item: LearningResourceImportItem): LearningResourceImportItem {
        val normalized = item.copy(
            sourceRecordKey = item.sourceRecordKey.safeText("sourceRecordKey", 512),
            title = item.title.safeText("title", 500),
            author = item.author?.safeOptionalText("author", 500),
            description = item.description?.safeOptionalText("description", 4_000, allowLines = true),
            category = item.category.safeText("category", 128),
            language = item.language.safeText("language", 16),
            coverUrl = item.coverUrl?.let(::strictPublicHttpsUrl),
            coverAlt = item.coverAlt?.safeOptionalText("coverAlt", 300),
            sourceUrl = strictPublicHttpsUrl(item.sourceUrl),
            contentSha256 = item.contentSha256.lowercase(),
        )
        if ((normalized.coverUrl == null) != (normalized.coverAlt == null)) {
            throw InvalidCatalogCommandException("coverUrl and coverAlt must be supplied together")
        }
        if (!SHA256.matches(normalized.contentSha256)) {
            throw InvalidCatalogCommandException("contentSha256 must be a lowercase SHA-256 digest")
        }
        return normalized
    }

    private fun canonicalManifest(command: LearningResourceImportCommand): String = buildString {
        append("catalog-learning-resource-import-v1")
        field(command.importId.toString())
        field(command.sourceName)
        field(command.sourceRevision)
        command.items.sortedBy { it.sourceRecordKey }.forEach { item ->
            field(item.resourceId.toString())
            field(item.sourceRecordKey)
            field(item.title)
            field(item.author.orEmpty())
            field(item.description.orEmpty())
            field(item.category)
            field(item.language)
            field(item.coverUrl.orEmpty())
            field(item.coverAlt.orEmpty())
            field(item.sourceUrl)
            field(item.contentSha256)
        }
    }

    private fun StringBuilder.field(value: String) {
        append('\u001f').append(value.length).append(':').append(value)
    }

    private fun String.safeText(name: String, maximum: Int, minimum: Int = 1): String {
        val normalized = trim()
        if (normalized.length !in minimum..maximum || normalized.any(Char::isISOControl)) {
            throw InvalidCatalogCommandException("$name must contain $minimum to $maximum non-control characters")
        }
        return normalized
    }

    private fun String.safeOptionalText(name: String, maximum: Int, allowLines: Boolean = false): String {
        val normalized = trim()
        val invalidControl = normalized.any { it.isISOControl() && (!allowLines || it != '\n' && it != '\t') }
        if (normalized.isEmpty() || normalized.length > maximum || invalidControl) {
            throw InvalidCatalogCommandException("$name must contain 1 to $maximum safe characters")
        }
        return normalized
    }

    private fun strictPublicHttpsUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse {
            throw InvalidCatalogCommandException("URLs must be public HTTPS URLs")
        }
        val host = runCatching { IDN.toASCII(uri.host.orEmpty()) }.getOrElse {
            throw InvalidCatalogCommandException("URLs must use a valid public hostname")
        }.lowercase()
        if (
            uri.scheme != "https" || host.isBlank() || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawFragment != null || uri.normalize().rawPath != uri.rawPath || value.length > 2_048 ||
            host == "localhost" || host.endsWith('.') || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || IPV4_LITERAL.matches(host) ||
            host.contains(':') || ENCODED_PATH_SEPARATOR.containsMatchIn(uri.rawPath.orEmpty())
        ) {
            throw InvalidCatalogCommandException("URLs must be canonical public HTTPS URLs")
        }
        return buildString {
            append("https://").append(host)
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val MAX_BATCH_SIZE = 250
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val IPV4_LITERAL = Regex("^[0-9.]+$")
        val ENCODED_PATH_SEPARATOR = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)
    }
}
