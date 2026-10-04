package com.mundiapolis.library.digitalcontent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class OpenApiContractTest {
    private val document = ObjectMapper().readTree(
        requireNotNull(javaClass.getResourceAsStream("/static/openapi/digital-content-v1.json")),
    )

    @Test
    fun `availability contract is scoped and does not expose storage provenance`() {
        val operation = document.path("paths")
            .path("/api/v1/digital-content/editions/{editionId}/availability")
            .path("get")
        assertNotNull(operation)
        assertEquals(
            "digital-content.availability.read",
            operation.path("security").path(0).path("oauth2").path(0).stringValue(),
        )

        val fields = document.path("components").path("schemas")
            .path("DownloadableFormat").path("properties")
        assertFalse(fields.has("objectKey"))
        assertFalse(fields.has("sourceUri"))

        val authorization = document.path("paths")
            .path("/api/v1/digital-content/assets/{assetId}/authorizations")
            .path("post")
        assertEquals(
            "digital-content.download.authorize",
            authorization.path("security").path(0).path("oauth2").path(0).stringValue(),
        )
        val responseFields = document.path("components").path("schemas")
            .path("DownloadAuthorization").path("properties")
        assertFalse(responseFields.has("objectKey"))
        assertFalse(responseFields.has("actorFingerprint"))

        val ingestion = document.path("paths")
            .path("/api/v1/digital-content/ingestions/{ingestionId}")
            .path("put")
        assertEquals(
            "digital-content.ingestion.create",
            ingestion.path("security").path(0).path("oauth2").path(0).stringValue(),
        )
        val grant = document.path("components").path("schemas")
            .path("IngestionUploadGrant").path("properties")
        assertFalse(grant.has("sourceUri"))
        assertFalse(grant.has("actorFingerprint"))
    }
}
