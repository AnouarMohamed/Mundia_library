package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.adapter.outbound.persistence.JooqLegacyCatalogImportRepository
import com.mundiapolis.library.catalog.dto.InvalidCatalogActorException
import com.mundiapolis.library.catalog.dto.InvalidCatalogCommandException
import com.mundiapolis.library.catalog.dto.LegacyCatalogImportCommand
import com.mundiapolis.library.catalog.dto.LegacyCatalogImportItem
import com.mundiapolis.library.catalog.dto.LegacyCatalogImportResult
import com.mundiapolis.library.catalog.dto.LegacyCatalogReviewImportItem
import org.springframework.stereotype.Service
import java.net.IDN
import java.net.URI
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class LegacyCatalogImportService(
    private val repository: JooqLegacyCatalogImportRepository,
    private val clock: Clock,
) {
    fun importBatch(command: LegacyCatalogImportCommand): LegacyCatalogImportResult {
        if (!SHA256.matches(command.actorFingerprint)) {
            throw InvalidCatalogActorException("Catalog import actor fingerprint is invalid")
        }
        if (!SHA256.matches(command.sourceRevision)) {
            throw InvalidCatalogCommandException("sourceRevision must be a lowercase SHA-256 digest")
        }
        if (command.items.size !in 1..MAX_BATCH_SIZE) {
            throw InvalidCatalogCommandException("items must contain between 1 and $MAX_BATCH_SIZE records")
        }
        if (command.items.sumOf { it.reviews.size } > MAX_REVIEW_COUNT) {
            throw InvalidCatalogCommandException("items must contain at most $MAX_REVIEW_COUNT reviews")
        }
        requireUnique(command.items.map { it.workId }, "work IDs")
        requireUnique(command.items.map { it.editionId }, "edition IDs")
        requireUnique(command.items.map { it.isbn.lowercase() }, "ISBNs")
        requireUnique(command.items.flatMap { item -> item.reviews.map { it.reviewId } }, "review IDs")
        val items = command.items.map(::normalize)
        val normalized = command.copy(items = items)
        val manifest = sha256(canonicalManifest(normalized))
        return repository.importBatch(
            normalized,
            manifest,
            clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }

    fun importEvidence(importId: UUID): LegacyCatalogImportResult = repository.importEvidence(importId)

    private fun normalize(item: LegacyCatalogImportItem): LegacyCatalogImportItem {
        val normalized = item.copy(
            title = item.title.safeText("title", 500),
            author = item.author.safeText("author", 300),
            summary = item.summary.safeText("summary", 1_000, minimum = 0, allowLines = true),
            description = item.description.safeText("description", 10_000, minimum = 0, allowLines = true),
            genre = item.genre.safeText("genre", 120),
            isbn = item.isbn.safeText("isbn", 32),
            publisher = item.publisher.safeText("publisher", 300),
            language = item.language.safeText("language", 80),
            coverUrl = item.coverUrl?.let(::strictPublicHttpsUrl),
            coverColor = item.coverColor?.uppercase(),
            videoUrl = item.videoUrl?.let(::strictPublicHttpsUrl),
            createdAt = item.createdAt.truncatedTo(ChronoUnit.MICROS),
            updatedAt = item.updatedAt.truncatedTo(ChronoUnit.MICROS),
            reviews = item.reviews.map(::normalizeReview).sortedBy { it.reviewId },
            contentSha256 = item.contentSha256.lowercase(),
        )
        if (
            normalized.rating !in 0.0..5.0 || !normalized.rating.isFinite() ||
            normalized.ratingCount < 0 || normalized.publicationYear !in 1000..3000 ||
            normalized.pageCount !in 1..100_000 || normalized.updatedAt < normalized.createdAt ||
            normalized.coverColor?.matches(HEX_COLOR) == false ||
            !SHA256.matches(normalized.contentSha256)
        ) throw InvalidCatalogCommandException("Legacy catalog item metadata is invalid")
        requireUnique(normalized.reviews.map { it.memberId }, "review member IDs per work")
        if (normalized.ratingCount != normalized.reviews.size) {
            throw InvalidCatalogCommandException("ratingCount must equal the imported review count")
        }
        val derivedRating = if (normalized.reviews.isEmpty()) 0.0 else
            BigDecimal.valueOf(normalized.reviews.sumOf { it.rating }.toLong())
                .divide(BigDecimal.valueOf(normalized.reviews.size.toLong()), 2, RoundingMode.HALF_UP)
                .toDouble()
        if (kotlin.math.abs(normalized.rating - derivedRating) > RATING_TOLERANCE) {
            throw InvalidCatalogCommandException("rating must equal the imported review average")
        }
        if (sha256(canonicalItem(normalized, includeContentHash = false)) != normalized.contentSha256) {
            throw InvalidCatalogCommandException("contentSha256 does not match the canonical catalog item")
        }
        return normalized
    }

    private fun canonicalManifest(command: LegacyCatalogImportCommand): String = buildString {
        append(MANIFEST_VERSION)
        field(command.importId.toString())
        field(command.sourceRevision)
        command.items.sortedBy { it.editionId }.forEach { field(canonicalItem(it, includeContentHash = true)) }
    }

    private fun canonicalItem(item: LegacyCatalogImportItem, includeContentHash: Boolean): String = buildString {
        append(ITEM_VERSION)
        field(item.workId.toString())
        field(item.editionId.toString())
        field(item.contributorId.toString())
        field(item.title)
        field(item.author)
        field(item.summary)
        field(item.description)
        field(item.genre)
        field(item.rating.toString())
        field(item.ratingCount.toString())
        field(item.isbn)
        field(item.publisher)
        field(item.publicationYear.toString())
        field(item.language)
        field(item.pageCount.toString())
        field(item.coverUrl.orEmpty())
        field(item.coverColor.orEmpty())
        field(item.videoUrl.orEmpty())
        field(item.active.toString())
        field(item.createdAt.toEpochMilli().toString())
        field(item.updatedAt.toEpochMilli().toString())
        item.reviews.sortedBy { it.reviewId }.forEach { review ->
            field(review.reviewId.toString())
            field(review.memberId.toString())
            field(review.rating.toString())
            field(review.content)
            field(review.createdAt.toEpochMilli().toString())
            field(review.updatedAt.toEpochMilli().toString())
        }
        if (includeContentHash) field(item.contentSha256)
    }

    private fun normalizeReview(review: LegacyCatalogReviewImportItem): LegacyCatalogReviewImportItem {
        val normalized = review.copy(
            content = review.content.safeText("review content", 4_000, allowLines = true),
            createdAt = review.createdAt.truncatedTo(ChronoUnit.MICROS),
            updatedAt = review.updatedAt.truncatedTo(ChronoUnit.MICROS),
        )
        if (normalized.rating !in 1..5 || normalized.updatedAt < normalized.createdAt) {
            throw InvalidCatalogCommandException("Legacy catalog review metadata is invalid")
        }
        return normalized
    }

    private fun StringBuilder.field(value: String) {
        append('\u001f').append(value.length).append(':').append(value)
    }

    private fun String.safeText(
        name: String,
        maximum: Int,
        minimum: Int = 1,
        allowLines: Boolean = false,
    ): String {
        val normalized = trim()
        val invalidControl = normalized.any { it.isISOControl() && (!allowLines || it != '\n' && it != '\t') }
        if (normalized.length !in minimum..maximum || invalidControl) {
            throw InvalidCatalogCommandException("$name must contain $minimum to $maximum safe characters")
        }
        return normalized
    }

    private fun strictPublicHttpsUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse {
            throw InvalidCatalogCommandException("Catalog media URLs must be public HTTPS URLs")
        }
        val host = runCatching { IDN.toASCII(uri.host.orEmpty()) }.getOrNull()?.lowercase()
            ?: throw InvalidCatalogCommandException("Catalog media URLs must have a valid hostname")
        if (
            uri.scheme != "https" || host.isBlank() || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawFragment != null || uri.normalize().rawPath != uri.rawPath || value.length > 2_048 ||
            host == "localhost" || host.endsWith('.') || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || IPV4_LITERAL.matches(host) ||
            host.contains(':') || ENCODED_PATH_SEPARATOR.containsMatchIn(uri.rawPath.orEmpty())
        ) throw InvalidCatalogCommandException("Catalog media URLs must be canonical public HTTPS URLs")
        return buildString {
            append("https://").append(host)
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun requireUnique(values: List<Any>, label: String) {
        if (values.toSet().size != values.size) {
            throw InvalidCatalogCommandException("items must contain unique $label")
        }
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val MAX_BATCH_SIZE = 10
        const val MAX_REVIEW_COUNT = 1_000
        const val MANIFEST_VERSION = "catalog-legacy-import-v1"
        const val ITEM_VERSION = "catalog-legacy-item-v1"
        const val RATING_TOLERANCE = 0.000_001
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val HEX_COLOR = Regex("^#[0-9A-F]{6}$")
        val IPV4_LITERAL = Regex("^[0-9.]+$")
        val ENCODED_PATH_SEPARATOR = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)
    }
}
