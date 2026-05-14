import { Ratelimit } from "@upstash/ratelimit";
import redis from "@/database/redis";
import config from "@/lib/config";

const isDevelopment = process.env.NODE_ENV !== "production";
const isRateLimitDisabled = process.env.DISABLE_RATE_LIMIT === "true";
const hasRedisConfig = Boolean(
  config.env.upstash.redisUrl && config.env.upstash.redisToken
);

/**
 * Shared rate limiter for public API endpoints using Upstash Redis.
 * 
 * Strategy: Fixed Window
 * Limit: 200 requests per minute per identifier (usually IP address).
 * 
 * This helps protect the application from brute-force attacks and 
 * excessive API usage.
 */
const ratelimit = new Ratelimit({
  redis,
  limiter: Ratelimit.fixedWindow(200, "1m"),
  analytics: false,
  prefix: "@upstash/ratelimit",
});

/**
 * Enhanced limit method that handles development environments and missing 
 * configurations gracefully.
 * 
 * @param key - The unique identifier for the rate limit (e.g., user IP).
 * @returns A promise that resolves to the rate limit result.
 * 
 * In development mode or when Redis is not configured, it returns a 
 * mock "success" result to avoid blocking local testing.
 */
const originalLimit = ratelimit.limit.bind(ratelimit);
ratelimit.limit = async (key: string) => {
  // Bypass rate limiting in local development or if explicitly disabled.
  // This prevents network round-trips to external Upstash Redis during dev.
  if (isDevelopment || isRateLimitDisabled || !hasRedisConfig) {
    return {
      success: true,
      limit: 200,
      remaining: 200,
      reset: Date.now() + 60000,
      pending: Promise.resolve(),
    };
  }

  try {
    return await originalLimit(key);
  } catch (error) {
    console.error("Rate limit check failed:", error);
    // If rate limiting fails due to infrastructure issues, we fail open 
    // to avoid breaking the application.
    throw error;
  }
};

export default ratelimit;
