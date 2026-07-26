import { createHash } from "crypto";

import { Ratelimit } from "@upstash/ratelimit";

import redis from "@/database/redis";
import config from "@/lib/config";
import { getClientIp } from "@/lib/security/api-request";
import { logError, logWarn } from "@/lib/security/logger";

const hasRedisConfig = Boolean(
  config.env.upstash.redisUrl && config.env.upstash.redisToken,
);
const failClosed = ["staging", "production"].includes(
  config.env.appEnvironment,
);

const accountLimiter = new Ratelimit({
  redis,
  limiter: Ratelimit.slidingWindow(5, "15 m"),
  prefix: "@upstash/auth/account",
  analytics: false,
});

const ipLimiter = new Ratelimit({
  redis,
  limiter: Ratelimit.slidingWindow(20, "15 m"),
  prefix: "@upstash/auth/ip",
  analytics: false,
});

const pseudonymize = (value: string) =>
  createHash("sha256").update(value).digest("hex");

/**
 * Credential verification is fail-closed in production. Both the account and
 * trusted ingress IP budgets must allow an attempt.
 */
export async function allowCredentialAttempt(email: string) {
  if (!hasRedisConfig) {
    if (failClosed) {
      logWarn("auth.rate_limit_unavailable");
      return false;
    }
    return true;
  }

  try {
    const ip = await getClientIp();
    const [account, source] = await Promise.all([
      accountLimiter.limit(pseudonymize(email)),
      ipLimiter.limit(pseudonymize(ip)),
    ]);

    return account.success && source.success;
  } catch (error) {
    logError("auth.rate_limit_failed", error);
    return !failClosed;
  }
}
