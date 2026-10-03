package com.mundiapolis.library.notification

import com.google.protobuf.Timestamp
import com.mundiapolis.library.notification.adapter.`in`.events.NotificationIntentKafkaConsumer
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_DELIVERY
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_EMAIL_FEEDBACK_RECEIPT
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_INBOX
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_INTENT_RECEIPT
import com.mundiapolis.library.notification.adapter.outbound.persistence.jooq.generated.Tables.NOTIFICATION_PREFERENCE
import com.mundiapolis.library.notification.contract.v1.DeliveryChannel
import com.mundiapolis.library.notification.contract.v1.NotificationIntent
import com.mundiapolis.library.notification.dto.NotificationCategory
import com.mundiapolis.library.notification.dto.NotificationChannel
import com.mundiapolis.library.notification.dto.NotificationIntentCommand
import com.mundiapolis.library.notification.dto.NotificationIntentConflictException
import com.mundiapolis.library.notification.dto.UpdateNotificationPreferenceRequest
import com.mundiapolis.library.notification.service.NotificationIntentHandler
import com.mundiapolis.library.notification.service.EmailDeliveryStore
import com.mundiapolis.library.notification.service.NotificationService
import com.mundiapolis.library.notification.dto.EmailProviderReceipt
import com.mundiapolis.library.notification.dto.SesFeedbackEvent
import com.mundiapolis.library.notification.dto.SesFeedbackType
import com.mundiapolis.library.notification.service.SesFeedbackStore
import org.jooq.DSLContext
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationServiceIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var intentHandler: NotificationIntentHandler
    @Autowired lateinit var intentConsumer: NotificationIntentKafkaConsumer
    @Autowired lateinit var notificationService: NotificationService
    @Autowired lateinit var emailDeliveryStore: EmailDeliveryStore
    @Autowired lateinit var sesFeedbackStore: SesFeedbackStore

    @BeforeEach
    fun seed() {
        dsl.deleteFrom(NOTIFICATION_EMAIL_FEEDBACK_RECEIPT).execute()
        dsl.deleteFrom(NOTIFICATION_INTENT_RECEIPT).execute()
        dsl.deleteFrom(NOTIFICATION_DELIVERY).execute()
        dsl.deleteFrom(NOTIFICATION_INBOX).execute()
        dsl.deleteFrom(NOTIFICATION_PREFERENCE).execute()
        insert(FIRST_ID, MEMBER_ID, NOW, null)
        insert(SECOND_ID, MEMBER_ID, NOW.minusMinutes(1), NOW)
        insert(OTHER_ID, OTHER_MEMBER_ID, NOW.plusMinutes(1), null)
    }

    @Test
    fun `intent ingestion creates inbox deliveries and one durable receipt atomically`() {
        val command = intentCommand()

        val created = intentHandler.apply(command)
        val replay = intentHandler.apply(command.copy(offset = command.offset + 1))

        org.assertj.core.api.Assertions.assertThat(created.replayed).isFalse()
        org.assertj.core.api.Assertions.assertThat(replay.replayed).isTrue()
        org.assertj.core.api.Assertions.assertThat(replay.notificationId).isEqualTo(created.notificationId)
        org.assertj.core.api.Assertions.assertThat(
            dsl.fetchCount(NOTIFICATION_INBOX, NOTIFICATION_INBOX.SOURCE_EVENT_ID.eq(command.eventId)),
        ).isEqualTo(1)
        org.assertj.core.api.Assertions.assertThat(
            dsl.fetchCount(NOTIFICATION_DELIVERY, NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(created.notificationId)),
        ).isEqualTo(2)
        org.assertj.core.api.Assertions.assertThat(
            dsl.fetchCount(NOTIFICATION_INTENT_RECEIPT, NOTIFICATION_INTENT_RECEIPT.EVENT_ID.eq(command.eventId)),
        ).isEqualTo(1)
        org.assertj.core.api.Assertions.assertThat(
            dsl.select(NOTIFICATION_DELIVERY.STATUS).from(NOTIFICATION_DELIVERY)
                .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(created.notificationId))
                .fetchSet(NOTIFICATION_DELIVERY.STATUS),
        ).containsExactlyInAnyOrder("DELIVERED", "PENDING")
    }

    @Test
    fun `email delivery claims are exclusive and acknowledgements require lease ownership`() {
        val notificationId = intentHandler.apply(intentCommand()).notificationId
        val claimedAt = Instant.now().plusSeconds(1)
        val firstToken = UUID.randomUUID()
        val claimed = emailDeliveryStore.claimBatch(
            "worker-1",
            firstToken,
            claimedAt,
            claimedAt.plusSeconds(60),
            10,
            8,
        )

        org.assertj.core.api.Assertions.assertThat(claimed).hasSize(1)
        org.assertj.core.api.Assertions.assertThat(claimed.single().notificationId).isEqualTo(notificationId)
        org.assertj.core.api.Assertions.assertThat(claimed.single().attempt).isEqualTo(1)
        org.assertj.core.api.Assertions.assertThat(
            emailDeliveryStore.claimBatch(
                "worker-2",
                UUID.randomUUID(),
                claimedAt,
                claimedAt.plusSeconds(60),
                10,
                8,
            ),
        ).isEmpty()
        org.assertj.core.api.Assertions.assertThat(
            emailDeliveryStore.markDelivered(
                "wrong-worker",
                claimed.single(),
                EmailProviderReceipt("brevo", "provider-message-1"),
                claimedAt.plusSeconds(1),
            ),
        ).isFalse()
        org.assertj.core.api.Assertions.assertThat(
            emailDeliveryStore.markDelivered(
                "worker-1",
                claimed.single(),
                EmailProviderReceipt("brevo", "provider-message-1"),
                claimedAt.plusSeconds(1),
            ),
        ).isTrue()

        val record = dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq("EMAIL"))
            .fetchSingle()
        org.assertj.core.api.Assertions.assertThat(record.status).isEqualTo("DELIVERED")
        org.assertj.core.api.Assertions.assertThat(record.provider).isEqualTo("brevo")
        org.assertj.core.api.Assertions.assertThat(record.providerMessageRef).isEqualTo("provider-message-1")
        org.assertj.core.api.Assertions.assertThat(record.leaseToken).isNull()
    }

    @Test
    fun `expired email lease is reclaimed and fences the previous owner`() {
        intentHandler.apply(intentCommand())
        val firstClaimAt = Instant.now().plusSeconds(1)
        val first = emailDeliveryStore.claimBatch(
            "worker-1",
            UUID.randomUUID(),
            firstClaimAt,
            firstClaimAt.plusSeconds(1),
            1,
            8,
        ).single()
        val secondClaimAt = firstClaimAt.plusSeconds(2)
        val second = emailDeliveryStore.claimBatch(
            "worker-2",
            UUID.randomUUID(),
            secondClaimAt,
            secondClaimAt.plusSeconds(60),
            1,
            8,
        ).single()

        org.assertj.core.api.Assertions.assertThat(second.deliveryId).isEqualTo(first.deliveryId)
        org.assertj.core.api.Assertions.assertThat(second.attempt).isEqualTo(2)
        org.assertj.core.api.Assertions.assertThat(
            emailDeliveryStore.markDelivered(
                "worker-1",
                first,
                EmailProviderReceipt("brevo", "stale-message"),
                secondClaimAt,
            ),
        ).isFalse()
        org.assertj.core.api.Assertions.assertThat(
            emailDeliveryStore.markDelivered(
                "worker-2",
                second,
                EmailProviderReceipt("brevo", "accepted-message"),
                secondClaimAt,
            ),
        ).isTrue()
    }

    @Test
    fun `expired final attempt is moved to dead letter state`() {
        val notificationId = intentHandler.apply(intentCommand()).notificationId
        val firstClaimAt = Instant.now().plusSeconds(1)
        emailDeliveryStore.claimBatch(
            "worker-1",
            UUID.randomUUID(),
            firstClaimAt,
            firstClaimAt.plusSeconds(1),
            1,
            1,
        ).single()

        org.assertj.core.api.Assertions.assertThat(
            emailDeliveryStore.claimBatch(
                "worker-2",
                UUID.randomUUID(),
                firstClaimAt.plusSeconds(2),
                firstClaimAt.plusSeconds(60),
                1,
                1,
            ),
        ).isEmpty()
        val record = dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq("EMAIL"))
            .fetchSingle()
        org.assertj.core.api.Assertions.assertThat(record.status).isEqualTo("DEAD_LETTERED")
        org.assertj.core.api.Assertions.assertThat(record.lastErrorCode).isEqualTo("LEASE_EXPIRED")
        org.assertj.core.api.Assertions.assertThat(record.deadLetteredAt).isNotNull()
        org.assertj.core.api.Assertions.assertThat(record.leaseToken).isNull()
    }

    @Test
    fun `SES feedback is idempotent and terminal outcomes cannot regress`() {
        intentHandler.apply(intentCommand())
        val acceptedAt = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS)
        val delivery = emailDeliveryStore.claimBatch(
            "worker-1",
            UUID.randomUUID(),
            acceptedAt,
            acceptedAt.plusSeconds(60),
            1,
            8,
        ).single()
        org.assertj.core.api.Assertions.assertThat(
            emailDeliveryStore.markDelivered(
                "worker-1",
                delivery,
                EmailProviderReceipt("aws-ses", "ses-message-123"),
                acceptedAt,
            ),
        ).isTrue()

        val delivered = feedback(
            "50000000-0000-4000-8000-000000000001",
            delivery.deliveryId,
            SesFeedbackType.DELIVERY,
            acceptedAt.plusSeconds(2),
            "a".repeat(64),
        )
        val replayResults = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (1..8).map { offset ->
                executor.submit<Boolean> {
                    sesFeedbackStore.apply(delivered, acceptedAt.plusSeconds(2L + offset)).replayed
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        }
        org.assertj.core.api.Assertions.assertThat(replayResults.count { !it }).isEqualTo(1)
        org.assertj.core.api.Assertions.assertThat(replayResults.count { it }).isEqualTo(7)

        sesFeedbackStore.apply(
            feedback(
                "50000000-0000-4000-8000-000000000002",
                delivery.deliveryId,
                SesFeedbackType.BOUNCE,
                acceptedAt.plusSeconds(5),
                "b".repeat(64),
            ),
            acceptedAt.plusSeconds(6),
        )
        sesFeedbackStore.apply(
            feedback(
                "50000000-0000-4000-8000-000000000003",
                delivery.deliveryId,
                SesFeedbackType.DELIVERY,
                acceptedAt.plusSeconds(7),
                "c".repeat(64),
            ),
            acceptedAt.plusSeconds(8),
        )
        sesFeedbackStore.apply(
            feedback(
                "50000000-0000-4000-8000-000000000004",
                delivery.deliveryId,
                SesFeedbackType.COMPLAINT,
                acceptedAt.plusSeconds(9),
                "d".repeat(64),
            ),
            acceptedAt.plusSeconds(10),
        )

        val record = dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.DELIVERY_ID.eq(delivery.deliveryId))
            .fetchSingle()
        org.assertj.core.api.Assertions.assertThat(record.providerOutcome).isEqualTo("COMPLAINED")
        org.assertj.core.api.Assertions.assertThat(record.providerEventAt.toInstant())
            .isEqualTo(acceptedAt.plusSeconds(9))
        org.assertj.core.api.Assertions.assertThat(dsl.fetchCount(NOTIFICATION_EMAIL_FEEDBACK_RECEIPT)).isEqualTo(4)
    }

    private fun feedback(
        snsMessageId: String,
        deliveryId: UUID,
        type: SesFeedbackType,
        eventAt: Instant,
        digest: String,
    ) = SesFeedbackEvent(
        UUID.fromString(snsMessageId),
        deliveryId,
        "ses-message-123",
        type,
        eventAt,
        digest,
    )

    @Test
    fun `configured Kafka consumer durably ingests and commits a real broker record`() {
        val eventId = UUID.randomUUID()
        val occurredAt = Instant.now().minusSeconds(1)
        val intent = NotificationIntent.newBuilder()
            .setEventId(eventId.toString())
            .setEventVersion(1)
            .setSourceType("circulation.reservation.ready.v1")
            .setMemberId(MEMBER_ID.toString())
            .setCategory(
                com.mundiapolis.library.notification.contract.v1.NotificationCategory
                    .NOTIFICATION_CATEGORY_HOLD_READY,
            )
            .setSubject("Reserved title ready")
            .setBody("Your reserved title is ready for collection.")
            .setOccurredAt(
                Timestamp.newBuilder().setSeconds(occurredAt.epochSecond).setNanos(occurredAt.nano),
            )
            .addChannels(DeliveryChannel.DELIVERY_CHANNEL_IN_APP)
            .addChannels(DeliveryChannel.DELIVERY_CHANNEL_EMAIL)
            .build()
        val record = ProducerRecord(TOPIC, MEMBER_ID.toString(), intent.toByteArray()).also {
            it.header("content-type", "application/x-protobuf")
            it.header("event-id", eventId.toString())
            it.header("event-type", "notification.intent.requested")
            it.header("event-version", "1")
            it.header("schema-subject", SCHEMA_SUBJECT)
            it.header("schema-version", "1")
        }
        val metadata = KafkaProducer<String, ByteArray>(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ),
        ).use { producer -> producer.send(record).get(10, TimeUnit.SECONDS) }

        awaitCondition {
            dsl.fetchCount(
                NOTIFICATION_INTENT_RECEIPT,
                NOTIFICATION_INTENT_RECEIPT.EVENT_ID.eq(eventId),
            ) == 1 && intentConsumer.healthSnapshot(Instant.now()).lastProcessedOffset >= metadata.offset()
        }

        org.assertj.core.api.Assertions.assertThat(
            dsl.fetchCount(NOTIFICATION_INBOX, NOTIFICATION_INBOX.SOURCE_EVENT_ID.eq(eventId)),
        ).isEqualTo(1)
        val notificationId = requireNotNull(
            dsl.select(NOTIFICATION_INBOX.NOTIFICATION_ID)
                .from(NOTIFICATION_INBOX)
                .where(NOTIFICATION_INBOX.SOURCE_EVENT_ID.eq(eventId))
                .fetchOne(NOTIFICATION_INBOX.NOTIFICATION_ID),
        )
        org.assertj.core.api.Assertions.assertThat(
            dsl.select(NOTIFICATION_DELIVERY.CHANNEL)
                .from(NOTIFICATION_DELIVERY)
                .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
                .fetchSet(NOTIFICATION_DELIVERY.CHANNEL),
        ).containsExactlyInAnyOrder("IN_APP", "EMAIL")
    }

    @Test
    fun `intent replay with different bytes is rejected without a duplicate effect`() {
        val command = intentCommand()
        val created = intentHandler.apply(command)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            intentHandler.apply(command.copy(payloadSha256 = "b".repeat(64), offset = 20))
        }.isInstanceOf(NotificationIntentConflictException::class.java)

        org.assertj.core.api.Assertions.assertThat(
            dsl.fetchCount(NOTIFICATION_INBOX, NOTIFICATION_INBOX.SOURCE_EVENT_ID.eq(command.eventId)),
        ).isEqualTo(1)
        org.assertj.core.api.Assertions.assertThat(
            dsl.fetchCount(NOTIFICATION_DELIVERY, NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(created.notificationId)),
        ).isEqualTo(2)
    }

    @Test
    fun `email preference suppression is applied atomically while in-app remains mandatory`() {
        notificationService.updatePreference(
            MEMBER_ID,
            0,
            preferenceRequest(dueSoonEnabled = false),
        )

        val created = intentHandler.apply(intentCommand())
        val deliveries = dsl.select(
            NOTIFICATION_DELIVERY.CHANNEL,
            NOTIFICATION_DELIVERY.STATUS,
            NOTIFICATION_DELIVERY.NEXT_ATTEMPT_AT,
        )
            .from(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(created.notificationId))
            .fetchMap(NOTIFICATION_DELIVERY.CHANNEL)

        org.assertj.core.api.Assertions.assertThat(deliveries.getValue("IN_APP")[NOTIFICATION_DELIVERY.STATUS])
            .isEqualTo("DELIVERED")
        org.assertj.core.api.Assertions.assertThat(deliveries.getValue("EMAIL")[NOTIFICATION_DELIVERY.STATUS])
            .isEqualTo("SUPPRESSED")
        org.assertj.core.api.Assertions.assertThat(deliveries.getValue("EMAIL")[NOTIFICATION_DELIVERY.NEXT_ATTEMPT_AT])
            .isNull()
    }

    @Test
    fun `preference API requires strong versions and makes exact retries idempotent`() {
        mockMvc.perform(
            get("/api/v1/notifications/preferences/me")
                .with(memberJwt(MEMBER_ID, PREFERENCE_READ_SCOPE)),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()))
            .andExpect(jsonPath("$.emailEnabled").value(true))
            .andExpect(jsonPath("$.updatedAt").doesNotExist())

        val payload = """
            {
              "emailEnabled": true,
              "dueSoonEnabled": false,
              "overdueEnabled": true,
              "holdReadyEnabled": true,
              "accountStatusEnabled": true
            }
        """.trimIndent()
        mockMvc.perform(
            put("/api/v1/notifications/preferences/me")
                .with(memberJwt(MEMBER_ID, PREFERENCE_WRITE_SCOPE))
                .contentType("application/json")
                .content(payload),
        ).andExpect(status().isPreconditionRequired)

        fun update(body: String = payload, version: String = "\"0\"") = mockMvc.perform(
            put("/api/v1/notifications/preferences/me")
                .with(memberJwt(MEMBER_ID, PREFERENCE_WRITE_SCOPE))
                .header("If-Match", version)
                .contentType("application/json")
                .content(body),
        )
        update()
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.dueSoonEnabled").value(false))
            .andExpect(jsonPath("$.updatedAt").isString)
        update()
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))
        update(version = "\"999\"")
            .andExpect(status().isConflict)
        update(payload.replace("\"overdueEnabled\": true", "\"overdueEnabled\": false"))
            .andExpect(status().isConflict)
        update(payload.dropLast(2) + ",\n  \"unexpected\": true\n}", "\"1\"")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `concurrent identical initial preference updates converge on one version`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val attempts = (0 until 8).map {
                executor.submit<com.mundiapolis.library.notification.dto.NotificationPreference> {
                    start.await(5, TimeUnit.SECONDS)
                    notificationService.updatePreference(
                        MEMBER_ID,
                        0,
                        preferenceRequest(emailEnabled = false),
                    )
                }
            }
            start.countDown()
            val preferences = attempts.map { it.get(10, TimeUnit.SECONDS) }

            org.assertj.core.api.Assertions.assertThat(preferences.map { it.version }).containsOnly(1L)
            org.assertj.core.api.Assertions.assertThat(
                dsl.fetchCount(NOTIFICATION_PREFERENCE, NOTIFICATION_PREFERENCE.MEMBER_ID.eq(MEMBER_ID)),
            ).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent exact intent deliveries create one notification`() {
        val command = intentCommand()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val attempts = (0 until 8).map { index ->
                executor.submit<com.mundiapolis.library.notification.dto.NotificationIntentExecution> {
                    start.await(5, TimeUnit.SECONDS)
                    intentHandler.apply(command.copy(offset = index.toLong()))
                }
            }
            start.countDown()
            val results = attempts.map { it.get(10, TimeUnit.SECONDS) }

            org.assertj.core.api.Assertions.assertThat(results.count { !it.replayed }).isEqualTo(1)
            org.assertj.core.api.Assertions.assertThat(results.map { it.notificationId }.toSet()).hasSize(1)
            org.assertj.core.api.Assertions.assertThat(
                dsl.fetchCount(NOTIFICATION_INBOX, NOTIFICATION_INBOX.SOURCE_EVENT_ID.eq(command.eventId)),
            ).isEqualTo(1)
            org.assertj.core.api.Assertions.assertThat(
                dsl.fetchCount(NOTIFICATION_INTENT_RECEIPT, NOTIFICATION_INTENT_RECEIPT.EVENT_ID.eq(command.eventId)),
            ).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `inbox is caller-bound filtered bounded and keyset paginated`() {
        val first = mockMvc.perform(get("/api/v1/notifications/me?limit=1").with(memberJwt(MEMBER_ID, READ_SCOPE)))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].notificationId").value(FIRST_ID.toString()))
            .andExpect(jsonPath("$.nextCursor").isString)
            .andReturn()
        val cursor = tools.jackson.databind.ObjectMapper().readTree(first.response.contentAsString)["nextCursor"].stringValue()
        mockMvc.perform(get("/api/v1/notifications/me?limit=1&cursor=$cursor").with(memberJwt(MEMBER_ID, READ_SCOPE)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].notificationId").value(SECOND_ID.toString()))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
        mockMvc.perform(get("/api/v1/notifications/me?status=UNREAD").with(memberJwt(MEMBER_ID, READ_SCOPE)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
        mockMvc.perform(get("/api/v1/notifications/me?limit=101").with(memberJwt(MEMBER_ID, READ_SCOPE)))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/v1/notifications/me?cursor=not-canonical").with(memberJwt(MEMBER_ID, READ_SCOPE)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `mark read is idempotent and hides cross-member existence`() {
        val request = patch("/api/v1/notifications/$FIRST_ID/read").with(memberJwt(MEMBER_ID, WRITE_SCOPE))
        mockMvc.perform(request).andExpect(status().isOk).andExpect(jsonPath("$.readAt").isString)
        val firstReadAt = requireNotNull(dsl.select(NOTIFICATION_INBOX.READ_AT).from(NOTIFICATION_INBOX)
            .where(NOTIFICATION_INBOX.NOTIFICATION_ID.eq(FIRST_ID)).fetchOne(NOTIFICATION_INBOX.READ_AT))
        mockMvc.perform(patch("/api/v1/notifications/$FIRST_ID/read").with(memberJwt(MEMBER_ID, WRITE_SCOPE)))
            .andExpect(status().isOk)
        val replayReadAt = requireNotNull(dsl.select(NOTIFICATION_INBOX.READ_AT).from(NOTIFICATION_INBOX)
            .where(NOTIFICATION_INBOX.NOTIFICATION_ID.eq(FIRST_ID)).fetchOne(NOTIFICATION_INBOX.READ_AT))
        org.assertj.core.api.Assertions.assertThat(replayReadAt).isEqualTo(firstReadAt)
        mockMvc.perform(patch("/api/v1/notifications/$OTHER_ID/read").with(memberJwt(MEMBER_ID, WRITE_SCOPE)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `authentication scope and canonical member claim are mandatory`() {
        mockMvc.perform(get("/api/v1/notifications/me")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v1/notifications/me").with(jwt())).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/notifications/me").with(jwt().authorities(SimpleGrantedAuthority(READ_SCOPE))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/notifications/me").with(memberJwt(MEMBER_ID, WRITE_SCOPE)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            get("/api/v1/notifications/preferences/me").with(memberJwt(MEMBER_ID, READ_SCOPE)),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            put("/api/v1/notifications/preferences/me")
                .with(memberJwt(MEMBER_ID, PREFERENCE_READ_SCOPE))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content(tools.jackson.databind.ObjectMapper().writeValueAsString(preferenceRequest())),
        ).andExpect(status().isForbidden)
    }

    private fun insert(id: UUID, memberId: UUID, createdAt: OffsetDateTime, readAt: OffsetDateTime?) {
        dsl.insertInto(NOTIFICATION_INBOX)
            .set(NOTIFICATION_INBOX.NOTIFICATION_ID, id)
            .set(NOTIFICATION_INBOX.MEMBER_ID, memberId)
            .set(NOTIFICATION_INBOX.SOURCE_EVENT_ID, UUID.randomUUID())
            .set(NOTIFICATION_INBOX.SOURCE_TYPE, "circulation.loan.due-soon.v1")
            .set(NOTIFICATION_INBOX.CATEGORY, "DUE_SOON")
            .set(NOTIFICATION_INBOX.SUBJECT, "Loan due soon")
            .set(NOTIFICATION_INBOX.BODY, "A borrowed title is due soon.")
            .set(NOTIFICATION_INBOX.OCCURRED_AT, createdAt.minusSeconds(1))
            .set(NOTIFICATION_INBOX.CREATED_AT, createdAt)
            .set(NOTIFICATION_INBOX.READ_AT, readAt)
            .execute()
    }

    private fun intentCommand() = NotificationIntentCommand(
        eventId = UUID.randomUUID(),
        memberId = MEMBER_ID,
        sourceType = "circulation.loan.due-soon.v1",
        category = NotificationCategory.DUE_SOON,
        subject = "Loan due soon",
        body = "A borrowed title is due soon.",
        occurredAt = Instant.now().minusSeconds(1),
        channels = setOf(NotificationChannel.IN_APP, NotificationChannel.EMAIL),
        payloadSha256 = "a".repeat(64),
        topic = "mundia.notification.intents.v1",
        partition = 0,
        offset = 10,
    )

    private fun preferenceRequest(
        emailEnabled: Boolean = true,
        dueSoonEnabled: Boolean = true,
    ) = UpdateNotificationPreferenceRequest(
        emailEnabled = emailEnabled,
        dueSoonEnabled = dueSoonEnabled,
        overdueEnabled = true,
        holdReadyEnabled = true,
        accountStatusEnabled = true,
    )

    private fun memberJwt(memberId: UUID, scope: String) = jwt()
        .jwt { it.claim("membership_id", memberId.toString()) }
        .authorities(SimpleGrantedAuthority(scope))

    private fun ProducerRecord<String, ByteArray>.header(name: String, value: String) {
        headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("Condition did not become true")
            Thread.sleep(Duration.ofMillis(25))
        }
    }

    private companion object {
        val MEMBER_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val OTHER_MEMBER_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val FIRST_ID: UUID = UUID.fromString("f0000000-0000-4000-8000-000000000001")
        val SECOND_ID: UUID = UUID.fromString("e0000000-0000-4000-8000-000000000002")
        val OTHER_ID: UUID = UUID.fromString("d0000000-0000-4000-8000-000000000003")
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 9, 28, 12, 0, 0, 0, ZoneOffset.UTC)
        const val READ_SCOPE = "SCOPE_notification.inbox.read"
        const val WRITE_SCOPE = "SCOPE_notification.inbox.write"
        const val PREFERENCE_READ_SCOPE = "SCOPE_notification.preferences.read"
        const val PREFERENCE_WRITE_SCOPE = "SCOPE_notification.preferences.write"
        const val TOPIC = "mundia.notification.intents.v1"
        const val SCHEMA_SUBJECT = "mundia.notification.v1.NotificationIntent"

        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("notification").withUsername("notification").withPassword("integration-test-only")

        @Container @JvmStatic
        val kafka = KafkaContainer("apache/kafka-native:4.2.0")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("app.intent-consumer.enabled") { "true" }
            registry.add("app.intent-consumer.instance-id") { "notification-integration-test" }
            registry.add("app.intent-consumer.group-id") { "notification-integration-test" }
            registry.add("app.intent-consumer.poll-timeout") { "PT0.1S" }
            registry.add("app.intent-consumer.maximum-poll-silence") { "PT10S" }
            registry.add("app.intent-consumer.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("app.intent-consumer.kafka.security-protocol") { "PLAINTEXT" }
            registry.add("app.intent-consumer.kafka.allow-insecure-transport") { "true" }
            registry.add("app.security.jwt.issuer") { "https://issuer.example.test" }
            registry.add("app.security.jwt.jwk-set-uri") { "https://issuer.example.test/.well-known/jwks.json" }
            registry.add("app.security.jwt.audience") { "notification-api" }
        }
    }
}
