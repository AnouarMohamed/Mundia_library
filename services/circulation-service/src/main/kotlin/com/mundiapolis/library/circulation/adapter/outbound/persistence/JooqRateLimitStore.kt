package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.application.model.RateLimitDecision
import com.mundiapolis.library.circulation.application.port.outbound.RateLimitStore
import com.mundiapolis.library.circulation.application.port.outbound.RateLimitMaintenanceStore
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqRateLimitStore(
    private val dsl: DSLContext,
) : RateLimitStore, RateLimitMaintenanceStore {
    override fun consume(
        principalFingerprint: String,
        bucketKey: String,
        limit: Int,
        window: Duration,
        now: Instant,
    ): RateLimitDecision {
        val expiresAt = now.plus(window)
        val record = dsl.resultQuery(
            """
            INSERT INTO circulation_rate_limit_bucket (
                principal_fingerprint,
                bucket_key,
                request_count,
                window_started_at,
                expires_at
            ) VALUES (?, ?, 1, CAST(? AS timestamp with time zone), CAST(? AS timestamp with time zone))
            ON CONFLICT (principal_fingerprint, bucket_key) DO UPDATE
            SET request_count = CASE
                    WHEN circulation_rate_limit_bucket.expires_at <= CAST(? AS timestamp with time zone)
                        THEN 1
                    ELSE LEAST(circulation_rate_limit_bucket.request_count + 1, CAST(? AS integer) + 1)
                END,
                window_started_at = CASE
                    WHEN circulation_rate_limit_bucket.expires_at <= CAST(? AS timestamp with time zone)
                        THEN CAST(? AS timestamp with time zone)
                    ELSE circulation_rate_limit_bucket.window_started_at
                END,
                expires_at = CASE
                    WHEN circulation_rate_limit_bucket.expires_at <= CAST(? AS timestamp with time zone)
                        THEN CAST(? AS timestamp with time zone)
                    ELSE circulation_rate_limit_bucket.expires_at
                END
            RETURNING request_count, expires_at
            """.trimIndent(),
            principalFingerprint,
            bucketKey,
            now.toOffsetDateTime(),
            expiresAt.toOffsetDateTime(),
            now.toOffsetDateTime(),
            limit,
            now.toOffsetDateTime(),
            now.toOffsetDateTime(),
            now.toOffsetDateTime(),
            expiresAt.toOffsetDateTime(),
        ).fetchOne() ?: error("Rate-limit update returned no row")
        val count = requireNotNull(record.get("request_count", Int::class.javaObjectType))
        val reset = requireNotNull(record.get("expires_at", OffsetDateTime::class.java)).toInstant()
        return RateLimitDecision(
            allowed = count <= limit,
            limit = limit,
            remaining = (limit - count).coerceAtLeast(0),
            resetsAt = reset,
        )
    }

    override fun deleteExpired(cutoff: Instant, batchSize: Int): Int = dsl.execute(
        """
        DELETE FROM circulation_rate_limit_bucket
        WHERE ctid IN (
            SELECT ctid
            FROM circulation_rate_limit_bucket
            WHERE expires_at < CAST(? AS timestamp with time zone)
            ORDER BY expires_at
            LIMIT CAST(? AS integer)
        )
        """.trimIndent(),
        cutoff.toOffsetDateTime(),
        batchSize,
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
