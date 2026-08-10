import { createHash } from "node:crypto";

import { Ratelimit } from "@upstash/ratelimit";
import redis from "@/database/redis";
import config from "@/lib/config";
import {
  applyPostgresRateLimit,
  type DistributedRateLimitResult,
} from "@/lib/security/postgres-rate-limit";

const isRateLimitDisabled = process.env.DISABLE_RATE_LIMIT === "true";
const isLocalEnvironment = ["development", "test"].includes(
  config.env.appEnvironment,
);
const hasRedisConfig = Boolean(
  config.env.upstash.redisUrl && config.env.upstash.redisToken
);

type DistributedRateLimitOptions = {
  scope: string;
  identifier: string;
  limit: number;
};

const redisLimiters = new Map<string, Ratelimit>();

const getRedisLimiter = (scope: string, limit: number) => {
  const cacheKey = `${scope}:${limit}`;
  const existing = redisLimiters.get(cacheKey);
  if (existing) return existing;

  const limiter = new Ratelimit({
    redis,
    limiter: Ratelimit.fixedWindow(limit, "1m"),
    analytics: false,
    prefix: `@upstash/ratelimit/${scope}`,
  });
  redisLimiters.set(cacheKey, limiter);
  return limiter;
};

const deniedResult = (limit: number): DistributedRateLimitResult => ({
  success: false,
  limit,
  remaining: 0,
  reset: Date.now() + 60_000,
  pending: Promise.resolve(),
  unavailable: true,
});

const localBypassResult = (limit: number): DistributedRateLimitResult => ({
  success: true,
  limit,
  remaining: limit,
  reset: Date.now() + 60_000,
  pending: Promise.resolve(),
});

/**
 * Apply an atomic, cross-instance one-minute budget. Protected tiers fail
 * closed when their selected backend is unavailable. Local development can
 * deliberately run without infrastructure, but production cannot enable that
 * bypass through configuration.
 */
export async function applyDistributedRateLimit({
  scope,
  identifier,
  limit,
}: DistributedRateLimitOptions): Promise<DistributedRateLimitResult> {
  if (!/^[a-z0-9:_-]{1,64}$/.test(scope)) {
    throw new Error("Rate-limit scope is invalid");
  }
  if (!Number.isInteger(limit) || limit < 1 || limit > 100_000) {
    throw new Error("Rate limit is outside the admitted range");
  }
  const privacySafeIdentifier = createHash("sha256")
    .update(identifier.slice(0, 1024))
    .digest("hex");

  if (isLocalEnvironment && (isRateLimitDisabled || !hasRedisConfig)) {
    return localBypassResult(limit);
  }

  if (config.env.rateLimitBackend === "postgres") {
    try {
      return await applyPostgresRateLimit({
        scope,
        identifier: privacySafeIdentifier,
        limit,
        windowSeconds: 60,
      });
    } catch (error) {
      console.error("PostgreSQL rate limit check failed:", error);
      return deniedResult(limit);
    }
  }

  if (!hasRedisConfig) {
    return deniedResult(limit);
  }

  try {
    const result = await getRedisLimiter(scope, limit).limit(
      privacySafeIdentifier,
    );
    return {
      success: result.success,
      limit: result.limit,
      remaining: result.remaining,
      reset: result.reset,
      pending: result.pending.then(() => undefined),
    };
  } catch (error) {
    console.error("Rate limit check failed:", error);
    return deniedResult(limit);
  }
}

/** Backward-compatible public API budget used by existing route guards. */
const ratelimit = {
  limit: (identifier: string) =>
    applyDistributedRateLimit({
      scope: "public-api",
      identifier,
      limit: 200,
    }),
};

export default ratelimit;
