package com.mundiapolis.library.catalog.dto

import java.time.Instant
import java.util.UUID

data class CatalogAuthorInput(
    val contributorId: UUID,
    val name: String,
    val bio: String?,
)

data class CreateWorkCommand(
    val workId: UUID,
    val title: String,
    val summary: String,
    val description: String,
    val genre: String,
    val authors: List<CatalogAuthorInput>,
    val reason: String,
    val idempotencyKey: String,
    val ownerFingerprint: String,
)

data class CreateEditionCommand(
    val editionId: UUID,
    val workId: UUID,
    val title: String,
    val isbn: String,
    val publisher: String,
    val publicationYear: Int,
    val language: String,
    val pageCount: Int,
    val coverUrl: String?,
    val coverColor: String?,
    val videoUrl: String?,
    val active: Boolean,
    val reason: String,
    val idempotencyKey: String,
    val ownerFingerprint: String,
)

data class CatalogCommandResult(
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val occurredAt: Instant,
)

data class CatalogCommandExecution(
    val result: CatalogCommandResult,
    val replayed: Boolean,
)

class CatalogCommandConflictException(message: String) : RuntimeException(message)

class CatalogIdempotencyConflictException :
    RuntimeException("Idempotency key was already used for different catalog input")

class CatalogIdempotencyIncompleteException :
    RuntimeException("The prior catalog command has no completed response")

class InvalidCatalogCommandException(message: String) : RuntimeException(message)

class InvalidCatalogActorException(message: String) : RuntimeException(message)
