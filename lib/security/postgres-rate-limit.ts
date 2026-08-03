import { createHash } from "node:crypto";

import { sql } from "drizzle-orm";

import { db } from "@/database/drizzle";

export type DistributedRateLimitResult = {
  success: boolean;
  limit: number;
  remaining: number;
  reset: number;
  pending: Promise<void>;
};

type RateLimitOptions = {
  scope: string;
  identifier: string;
  limit: number;
  windowSeconds: number;
};

let lastCleanupAt = 0;
const CLEANUP_INTERVAL_MS = 5 * 60 * 1000;

const boundedInteger = (
  value: number,
  minimum: number,
  maximum: number,
  label: string,
) => {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${label} is outside the admitted range`);
  }
  return value;
};

const cleanupExpiredBuckets = async () => {
  const now = Date.now();
  if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return;
  lastCleanupAt = now;

  await db.execute(sql`
    DELETE FROM "rate_limit_buckets"
    WHERE ctid IN (
      SELECT ctid
      FROM "rate_limit_buckets"
      WHERE "expires_at" < statement_timestamp() - interval '1 hour'
      ORDER BY "expires_at"
      LIMIT 500
    )
  `);
};

/**
 * Increment a cross-instance fixed-window budget atomically in PostgreSQL.
 * The identifier is bounded and hashed before it reaches durable storage.
 */
export async function applyPostgresRateLimit({
  scope,
  identifier,
  limit,
  windowSeconds,
}: RateLimitOptions): Promise<DistributedRateLimitResult> {
  if (!/^[a-z0-9:_-]{1,64}$/.test(scope)) {
    throw new Error("Rate-limit scope is invalid");
  }
  const admittedLimit = boundedInteger(limit, 1, 100_000, "Rate limit");
  const admittedWindow = boundedInteger(
    windowSeconds,
    1,
    86_400,
    "Rate-limit window",
  );
  const identifierHash = createHash("sha256")
    .update(identifier.slice(0, 1024))
    .digest("hex");

  const rows = await db.execute<{
    request_count: number;
    expires_at: Date | string;
  }>(sql`
    INSERT INTO "rate_limit_buckets" (
      "scope",
      "identifier_hash",
      "request_count",
      "window_started_at",
      "expires_at"
    )
    VALUES (
      ${scope},
      ${identifierHash},
      1,
      statement_timestamp(),
      statement_timestamp() + ${admittedWindow} * interval '1 second'
    )
    ON CONFLICT ("scope", "identifier_hash") DO UPDATE
    SET
      "request_count" = CASE
        WHEN "rate_limit_buckets"."expires_at" <= EXCLUDED."window_started_at"
          THEN 1
        ELSE "rate_limit_buckets"."request_count" + 1
      END,
      "window_started_at" = CASE
        WHEN "rate_limit_buckets"."expires_at" <= EXCLUDED."window_started_at"
          THEN EXCLUDED."window_started_at"
        ELSE "rate_limit_buckets"."window_started_at"
      END,
      "expires_at" = CASE
        WHEN "rate_limit_buckets"."expires_at" <= EXCLUDED."window_started_at"
          THEN EXCLUDED."expires_at"
        ELSE "rate_limit_buckets"."expires_at"
      END
    RETURNING "request_count", "expires_at"
  `);

  const row = rows.rows[0];
  if (!row) throw new Error("Rate-limit update returned no row");

  await cleanupExpiredBuckets();

  const count = Number(row.request_count);
  return {
    success: count <= admittedLimit,
    limit: admittedLimit,
    remaining: Math.max(0, admittedLimit - count),
    reset: new Date(row.expires_at).getTime(),
    pending: Promise.resolve(),
  };
}
