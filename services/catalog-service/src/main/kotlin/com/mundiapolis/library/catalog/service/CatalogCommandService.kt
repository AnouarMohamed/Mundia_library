package com.mundiapolis.library.catalog.service

import com.mundiapolis.library.catalog.adapter.outbound.persistence.JooqCatalogCommandRepository
import com.mundiapolis.library.catalog.dto.CatalogAuthorInput
import com.mundiapolis.library.catalog.dto.CatalogCommandExecution
import com.mundiapolis.library.catalog.dto.CreateEditionCommand
import com.mundiapolis.library.catalog.dto.CreateWorkCommand
import com.mundiapolis.library.catalog.dto.InvalidCatalogCommandException
import com.mundiapolis.library.catalog.dto.InvalidCatalogActorException
import org.springframework.stereotype.Service
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat

@Service
class CatalogCommandService(
    private val repository: JooqCatalogCommandRepository,
    private val clock: Clock,
) {
    fun createWork(command: CreateWorkCommand): CatalogCommandExecution {
        command.ownerFingerprint.requireValidOwner()
        val normalized = command.copy(
            title = command.title.requiredText("title", 500),
            summary = command.summary.boundedText("summary", 1_000),
            description = command.description.boundedText("description", 10_000),
            genre = command.genre.requiredText("genre", 120),
            authors = normalizeAuthors(command.authors),
            reason = command.reason.requiredText("reason", 500, minimum = 8),
            idempotencyKey = command.idempotencyKey.validIdempotencyKey(),
        )
        val fingerprint = fingerprint(
            "CREATE_WORK",
            normalized.workId.toString(),
            normalized.title,
            normalized.summary,
            normalized.description,
            normalized.genre,
            *normalized.authors.flatMap { author ->
                listOf(author.contributorId.toString(), author.name, author.bio ?: NULL_MARKER)
            }.toTypedArray(),
            normalized.reason,
        )
        return repository.createWork(
            normalized,
            fingerprint,
            clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }

    fun createEdition(command: CreateEditionCommand): CatalogCommandExecution {
        command.ownerFingerprint.requireValidOwner()
        val normalized = command.copy(
            title = command.title.requiredText("title", 500),
            isbn = command.isbn.requiredText("isbn", 32),
            publisher = command.publisher.requiredText("publisher", 300),
            language = command.language.requiredText("language", 80),
            coverUrl = command.coverUrl.optionalHttpsUrl("coverUrl"),
            coverColor = command.coverColor.optionalCoverColor(),
            videoUrl = command.videoUrl.optionalHttpsUrl("videoUrl"),
            reason = command.reason.requiredText("reason", 500, minimum = 8),
            idempotencyKey = command.idempotencyKey.validIdempotencyKey(),
        )
        if (normalized.publicationYear !in 1000..3000) {
            throw InvalidCatalogCommandException("publicationYear must be between 1000 and 3000")
        }
        if (normalized.pageCount !in 1..100_000) {
            throw InvalidCatalogCommandException("pageCount must be between 1 and 100000")
        }
        val fingerprint = fingerprint(
            "CREATE_EDITION",
            normalized.editionId.toString(),
            normalized.workId.toString(),
            normalized.title,
            normalized.isbn,
            normalized.publisher,
            normalized.publicationYear.toString(),
            normalized.language,
            normalized.pageCount.toString(),
            normalized.coverUrl ?: NULL_MARKER,
            normalized.coverColor ?: NULL_MARKER,
            normalized.videoUrl ?: NULL_MARKER,
            normalized.active.toString(),
            normalized.reason,
        )
        return repository.createEdition(
            normalized,
            fingerprint,
            clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }

    private fun normalizeAuthors(authors: List<CatalogAuthorInput>): List<CatalogAuthorInput> {
        if (authors.isEmpty() || authors.size > MAX_AUTHORS) {
            throw InvalidCatalogCommandException("authors must contain between 1 and $MAX_AUTHORS entries")
        }
        if (authors.map(CatalogAuthorInput::contributorId).toSet().size != authors.size) {
            throw InvalidCatalogCommandException("authors must not contain duplicate contributor IDs")
        }
        return authors.map { author ->
            author.copy(
                name = author.name.requiredText("author name", 300),
                bio = author.bio?.boundedText("author bio", 5_000)?.takeIf(String::isNotEmpty),
            )
        }
    }

    private fun String.requiredText(name: String, maximum: Int, minimum: Int = 1): String {
        val normalized = trim()
        if (normalized.length !in minimum..maximum || normalized.any(Char::isISOControl)) {
            throw InvalidCatalogCommandException(
                "$name must contain $minimum to $maximum non-control characters",
            )
        }
        return normalized
    }

    private fun String.boundedText(name: String, maximum: Int): String {
        val normalized = trim()
        if (normalized.length > maximum || normalized.any { it.isISOControl() && it != '\n' && it != '\t' }) {
            throw InvalidCatalogCommandException("$name must not exceed $maximum safe characters")
        }
        return normalized
    }

    private fun String.validIdempotencyKey(): String {
        if (length !in 16..128 || any { it.code !in 32..126 }) {
            throw InvalidCatalogCommandException(
                "Idempotency-Key must contain 16 to 128 visible ASCII characters",
            )
        }
        return this
    }

    private fun String.requireValidOwner() {
        if (!OWNER_FINGERPRINT.matches(this)) {
            throw InvalidCatalogActorException("Catalog command actor fingerprint is invalid")
        }
    }

    private fun String?.optionalHttpsUrl(name: String): String? {
        val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.length > 2_048 || value.any(Char::isISOControl)) {
            throw InvalidCatalogCommandException("$name must be a bounded HTTPS URL")
        }
        val uri = runCatching { URI(value) }.getOrNull()
        if (
            uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawFragment != null
        ) {
            throw InvalidCatalogCommandException("$name must be an absolute HTTPS URL")
        }
        return uri.toASCIIString()
    }

    private fun String?.optionalCoverColor(): String? {
        val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!COVER_COLOR.matches(value)) {
            throw InvalidCatalogCommandException("coverColor must be a six-digit hexadecimal color")
        }
        return value.uppercase()
    }

    private fun fingerprint(operation: String, vararg values: String): String {
        val canonical = buildString {
            append("catalog-command-v1")
            append('\u001f')
            append(operation)
            values.forEach { value ->
                append('\u001f')
                append(value.length)
                append(':')
                append(value)
            }
        }
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private companion object {
        const val MAX_AUTHORS = 20
        const val NULL_MARKER = "<null>"
        val COVER_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        val OWNER_FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}
