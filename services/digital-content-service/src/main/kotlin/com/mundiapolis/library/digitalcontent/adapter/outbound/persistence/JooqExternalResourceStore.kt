package com.mundiapolis.library.digitalcontent.adapter.outbound.persistence

import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_EXTERNAL_AUTHORIZATION_AUDIT
import com.mundiapolis.library.digitalcontent.adapter.outbound.persistence.jooq.generated.Tables.DIGITAL_CONTENT_EXTERNAL_RESOURCE
import com.mundiapolis.library.digitalcontent.dto.ExternalResourceAvailability
import com.mundiapolis.library.digitalcontent.dto.ExternalResourceRegistration
import com.mundiapolis.library.digitalcontent.service.AuthorizableExternalResource
import com.mundiapolis.library.digitalcontent.service.ExternalResourceConflictException
import com.mundiapolis.library.digitalcontent.service.ExternalResourceRegistrationCommand
import com.mundiapolis.library.digitalcontent.service.ExternalResourceStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqExternalResourceStore(
    private val dsl: DSLContext,
) : ExternalResourceStore {
    override fun register(command: ExternalResourceRegistrationCommand): ExternalResourceRegistration {
        val verifiedAt = command.verifiedAt.atOffset(ZoneOffset.UTC)
        val inserted = dsl.insertInto(DIGITAL_CONTENT_EXTERNAL_RESOURCE)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RESOURCE_ID, command.resourceId)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.SOURCE_PROVIDER, command.sourceProvider)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.SOURCE_URI, command.sourceUrl)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.DOWNLOAD_URI, command.downloadUrl)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.MEDIA_TYPE, command.mediaType)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.LICENSE_EXPRESSION, command.licenseExpression)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.LICENSE_URI, command.licenseUrl)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.ATTRIBUTION, command.attribution)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RIGHTS_STATUS, "VERIFIED")
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RIGHTS_VERIFIED_AT, verifiedAt)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.TERRITORY_SCOPE, "GLOBAL")
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.MANIFEST_SHA256, command.manifestSha256)
            .set(DIGITAL_CONTENT_EXTERNAL_RESOURCE.REGISTERED_BY, command.actorFingerprint)
            .onConflict(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RESOURCE_ID)
            .doNothing()
            .execute()
        val record = dsl.select(
            DIGITAL_CONTENT_EXTERNAL_RESOURCE.SOURCE_PROVIDER,
            DIGITAL_CONTENT_EXTERNAL_RESOURCE.LICENSE_EXPRESSION,
            DIGITAL_CONTENT_EXTERNAL_RESOURCE.MANIFEST_SHA256,
            DIGITAL_CONTENT_EXTERNAL_RESOURCE.REGISTERED_BY,
        )
            .from(DIGITAL_CONTENT_EXTERNAL_RESOURCE)
            .where(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RESOURCE_ID.eq(command.resourceId))
            .forShare()
            .fetchSingle()
        if (
            record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.MANIFEST_SHA256] != command.manifestSha256 ||
            record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.REGISTERED_BY] != command.actorFingerprint
        ) {
            throw ExternalResourceConflictException()
        }
        return ExternalResourceRegistration(
            command.resourceId,
            requireNotNull(record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.SOURCE_PROVIDER]),
            requireNotNull(record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.LICENSE_EXPRESSION]),
            inserted == 0,
        )
    }

    override fun findAvailable(resourceId: UUID, now: Instant): ExternalResourceAvailability {
        val timestamp = now.atOffset(ZoneOffset.UTC)
        val record = availableQuery(resourceId, timestamp)
            .fetchOne()
            ?: return ExternalResourceAvailability(resourceId, false, null, null, null)
        return ExternalResourceAvailability(
            resourceId,
            true,
            record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.SOURCE_PROVIDER],
            record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.MEDIA_TYPE],
            record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.LICENSE_EXPRESSION],
        )
    }

    override fun lockAuthorizable(resourceId: UUID, now: Instant): AuthorizableExternalResource? {
        val timestamp = now.atOffset(ZoneOffset.UTC)
        return availableQuery(resourceId, timestamp)
            .forShare()
            .fetchOne { record ->
                AuthorizableExternalResource(
                    resourceId,
                    requireNotNull(record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.SOURCE_PROVIDER]),
                    requireNotNull(record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.LICENSE_EXPRESSION]),
                    requireNotNull(record[DIGITAL_CONTENT_EXTERNAL_RESOURCE.DOWNLOAD_URI]),
                )
            }
    }

    override fun recordAuthorization(
        authorizationId: UUID,
        resourceId: UUID,
        actorFingerprint: String,
        issuedAt: Instant,
    ) {
        dsl.insertInto(DIGITAL_CONTENT_EXTERNAL_AUTHORIZATION_AUDIT)
            .set(DIGITAL_CONTENT_EXTERNAL_AUTHORIZATION_AUDIT.AUTHORIZATION_ID, authorizationId)
            .set(DIGITAL_CONTENT_EXTERNAL_AUTHORIZATION_AUDIT.RESOURCE_ID, resourceId)
            .set(DIGITAL_CONTENT_EXTERNAL_AUTHORIZATION_AUDIT.ACTOR_FINGERPRINT, actorFingerprint)
            .set(
                DIGITAL_CONTENT_EXTERNAL_AUTHORIZATION_AUDIT.ISSUED_AT,
                issuedAt.atOffset(ZoneOffset.UTC),
            )
            .execute()
    }

    private fun availableQuery(resourceId: UUID, now: OffsetDateTime) = dsl.select(
        DIGITAL_CONTENT_EXTERNAL_RESOURCE.SOURCE_PROVIDER,
        DIGITAL_CONTENT_EXTERNAL_RESOURCE.MEDIA_TYPE,
        DIGITAL_CONTENT_EXTERNAL_RESOURCE.LICENSE_EXPRESSION,
        DIGITAL_CONTENT_EXTERNAL_RESOURCE.DOWNLOAD_URI,
    )
        .from(DIGITAL_CONTENT_EXTERNAL_RESOURCE)
        .where(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RESOURCE_ID.eq(resourceId))
        .and(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RIGHTS_STATUS.eq("VERIFIED"))
        .and(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RIGHTS_VERIFIED_AT.le(now))
        .and(
            DIGITAL_CONTENT_EXTERNAL_RESOURCE.RIGHTS_EXPIRES_AT.isNull
                .or(DIGITAL_CONTENT_EXTERNAL_RESOURCE.RIGHTS_EXPIRES_AT.gt(now)),
        )
        .and(DIGITAL_CONTENT_EXTERNAL_RESOURCE.TERRITORY_SCOPE.eq("GLOBAL"))
}
