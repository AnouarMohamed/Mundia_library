package com.mundiapolis.library.catalog

import com.mundiapolis.library.catalog.adapter.outbound.events.ProtobufCatalogEventEncoder
import com.mundiapolis.library.catalog.config.CatalogOutboxProperties
import com.mundiapolis.library.catalog.contract.v1.CatalogEvent
import com.mundiapolis.library.catalog.dto.CatalogOutboxContractException
import com.mundiapolis.library.catalog.dto.CatalogOutboxPayloadTooLargeException
import com.mundiapolis.library.catalog.dto.ClaimedCatalogOutboxEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CatalogOutboxContractTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `work events encode to the immutable protobuf envelope`() {
        val aggregateId = UUID.fromString("71000000-0000-0000-0000-000000000001")
        val event = claimedEvent(
            aggregateId = aggregateId,
            aggregateType = "work",
            eventType = "catalog.work.updated",
            aggregateVersion = 4,
            payload = """
                {
                  "workId":"$aggregateId",
                  "title":"A Contract Work",
                  "summary":"A summary",
                  "description":"A description",
                  "genre":"Systems",
                  "rating":4.5,
                  "authors":[{
                    "contributorId":"72000000-0000-0000-0000-000000000001",
                    "name":"Contract Author",
                    "bio":null
                  }]
                }
            """.trimIndent(),
        )

        val encoded = ProtobufCatalogEventEncoder(objectMapper, properties()).encode(event)
        val contract = CatalogEvent.parseFrom(encoded.payload)

        assertEquals(event.eventId.toString(), contract.eventId)
        assertEquals("catalog.work.updated", contract.eventType)
        assertEquals(4, contract.aggregateVersion)
        assertEquals(aggregateId.toString(), contract.work.workId)
        assertEquals("Contract Author", contract.work.authorsList.single().name)
        assertEquals(false, contract.work.authorsList.single().hasBiography())
        assertEquals(aggregateId.toString(), encoded.key)
        assertEquals("mundia.catalog.v1.CatalogEvent", encoded.schemaSubject)
    }

    @Test
    fun `encoder blocks unknown contracts and valid oversized payloads`() {
        val aggregateId = UUID.fromString("73000000-0000-0000-0000-000000000001")
        val valid = claimedEvent(
            aggregateId = aggregateId,
            aggregateType = "work",
            eventType = "catalog.work.created",
            aggregateVersion = 0,
            payload = largeWorkPayload(aggregateId),
        )
        assertThrows(CatalogOutboxPayloadTooLargeException::class.java) {
            ProtobufCatalogEventEncoder(objectMapper, properties(maximumEventBytes = 1_024)).encode(valid)
        }
        assertThrows(CatalogOutboxContractException::class.java) {
            ProtobufCatalogEventEncoder(objectMapper, properties()).encode(
                valid.copy(eventType = "catalog.work.unknown"),
            )
        }
    }

    @Test
    fun `edition events encode validated media without inventory fields`() {
        val aggregateId = UUID.fromString("75000000-0000-0000-0000-000000000001")
        val payload = """
            {
              "editionId":"$aggregateId",
              "workId":"75000000-0000-0000-0000-000000000002",
              "title":"Contract Edition",
              "isbn":"9780000000750",
              "publisher":"Mundiapolis Press",
              "publicationYear":2026,
              "language":"English",
              "pageCount":240,
              "coverUrl":"https://images.example.test/cover.jpg",
              "coverColor":"#123ABC",
              "videoUrl":null,
              "isActive":true
            }
        """.trimIndent()
        val event = claimedEvent(
            aggregateId,
            "edition",
            "catalog.edition.created",
            aggregateVersion = 0,
            payload = payload,
        )
        val contract = CatalogEvent.parseFrom(
            ProtobufCatalogEventEncoder(objectMapper, properties()).encode(event).payload,
        )

        assertEquals(aggregateId.toString(), contract.edition.editionId)
        assertEquals("https://images.example.test/cover.jpg", contract.edition.coverUrl)
        assertEquals(true, contract.edition.isActive)
        assertThrows(CatalogOutboxContractException::class.java) {
            ProtobufCatalogEventEncoder(objectMapper, properties()).encode(
                event.copy(payloadJson = payload.replace("https://", "http://")),
            )
        }
    }

    @Test
    fun `enabled delivery configuration fails closed on insecure broker settings`() {
        assertTrue(properties().isSafeConfiguration)
        val unsafe = properties().copy(
            kafka = properties().kafka.copy(
                securityProtocol = "SASL_SSL",
                allowInsecureTransport = false,
                saslMechanism = null,
                saslJaasConfig = null,
            ),
        )
        assertFalse(unsafe.isSafeConfiguration)
    }

    private fun claimedEvent(
        aggregateId: UUID,
        aggregateType: String,
        eventType: String,
        aggregateVersion: Long,
        payload: String,
    ) = ClaimedCatalogOutboxEvent(
        eventId = UUID.fromString("70000000-0000-0000-0000-000000000001"),
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        aggregateVersion = aggregateVersion,
        eventType = eventType,
        eventVersion = 1,
        occurredAt = Instant.parse("2026-01-01T12:00:00Z"),
        payloadJson = payload,
        deliveryAttempt = 1,
        leaseToken = UUID.fromString("70000000-0000-0000-0000-000000000002"),
    )

    private fun largeWorkPayload(workId: UUID): String {
        val authors = (1..20).map { index ->
            mapOf(
                "contributorId" to UUID(0x7400000000000000L, index.toLong()).toString(),
                "name" to "Author $index",
                "bio" to "x".repeat(5_000),
            )
        }
        return objectMapper.writeValueAsString(
            mapOf(
                "workId" to workId.toString(),
                "title" to "Large Contract Work",
                "summary" to "A summary",
                "description" to "A description",
                "genre" to "Systems",
                "rating" to 4.0,
                "authors" to authors,
            ),
        )
    }

    private fun properties(maximumEventBytes: Int = 262_144) = CatalogOutboxProperties(
        enabled = true,
        instanceId = "catalog-test",
        topic = "mundia.catalog.events.v1",
        schemaSubject = "mundia.catalog.v1.CatalogEvent",
        pollInterval = Duration.ofMillis(500),
        leaseDuration = Duration.ofSeconds(90),
        batchSize = 10,
        maximumAttempts = 20,
        retryBaseDelay = Duration.ofSeconds(1),
        retryMaximumDelay = Duration.ofMinutes(5),
        publishedRetention = Duration.ofDays(30),
        cleanupInterval = Duration.ofHours(1),
        cleanupBatchSize = 1_000,
        maximumEventBytes = maximumEventBytes,
        maximumPendingAge = Duration.ofMinutes(5),
        kafka = CatalogOutboxProperties.KafkaProperties(
            bootstrapServers = listOf("localhost:9092"),
            securityProtocol = "PLAINTEXT",
            allowInsecureTransport = true,
            saslMechanism = null,
            saslJaasConfig = null,
            truststoreLocation = null,
            truststorePassword = null,
            keystoreLocation = null,
            keystorePassword = null,
            keyPassword = null,
            deliveryTimeout = Duration.ofSeconds(5),
            requestTimeout = Duration.ofSeconds(3),
            maximumBlock = Duration.ofSeconds(1),
        ),
    )
}
