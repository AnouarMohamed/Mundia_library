/**
 * Redis Connection Module
 *
 * This module initializes and exports a Redis client instance using Upstash Redis.
 * It is used for caching, rate limiting, and session management throughout the application.
 *
 * Similar to the database module, it implements a safety proxy to provide clear error
 * messages if Redis environment variables are missing during development or runtime.
 */

import { Redis } from "@upstash/redis";
import config from "@/lib/config";

/** Message shown when Redis configuration is missing. */
const missingRedisConfigMessage =
  "Upstash Redis is not configured. Please check UPSTASH_REDIS_URL and UPSTASH_REDIS_TOKEN.";

/** Creates a standard error for missing Redis configuration. */
const createMissingRedisConfigError = () => new Error(missingRedisConfigMessage);

/**
 * Fallback proxy that throws an error when any Redis property is accessed.
 * Used when Upstash Redis environment variables are missing to provide a clear error message.
 */
const createMissingRedis = () =>
  new Proxy(
    {},
    {
      get(_target, property) {
        if (property === "then") {
          return undefined;
        }

        throw createMissingRedisConfigError();
      },
    }
  ) as Redis;

/** Flag indicating if Redis configuration is present. */
const hasRedisConfig = Boolean(
  config.env.upstash.redisUrl && config.env.upstash.redisToken
);

/**
 * Main Redis client instance.
 * Automatically switches between a real Upstash Redis client and a safety proxy
 * based on the availability of configuration.
 */
const redis = hasRedisConfig
  ? new Redis({
      url: config.env.upstash.redisUrl,
      token: config.env.upstash.redisToken,
    })
  : createMissingRedis();

export default redis;
