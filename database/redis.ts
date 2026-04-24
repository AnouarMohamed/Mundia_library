import { Redis } from "@upstash/redis";
import config from "@/lib/config";

const missingRedisConfigMessage =
  "Upstash Redis is not configured. Please check UPSTASH_REDIS_URL and UPSTASH_REDIS_TOKEN.";

const createMissingRedisConfigError = () => new Error(missingRedisConfigMessage);

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

const hasRedisConfig = Boolean(
  config.env.upstash.redisUrl && config.env.upstash.redisToken
);

const redis = hasRedisConfig
  ? new Redis({
      url: config.env.upstash.redisUrl,
      token: config.env.upstash.redisToken,
    })
  : createMissingRedis();

export default redis;
