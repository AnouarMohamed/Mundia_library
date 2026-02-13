import { Ratelimit } from "@upstash/ratelimit";
import redis from "@/database/redis";

const isDevelopment = process.env.NODE_ENV !== "production";

const ratelimit = new Ratelimit({
  redis,
  limiter: Ratelimit.fixedWindow(200, "1m"), // 200 requests per minute per IP
  analytics: false,
  prefix: "@upstash/ratelimit",
});

// Wrap ratelimit.limit() to handle development mode gracefully
const originalLimit = ratelimit.limit.bind(ratelimit);
ratelimit.limit = async (key: string) => {
  // Avoid external Redis network round-trips in local development.
  if (isDevelopment) {
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
    throw error;
  }
};

export default ratelimit;
