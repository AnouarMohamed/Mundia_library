import { beforeEach, describe, expect, it, vi } from "vitest";

const applyPostgresRateLimit = vi.hoisted(() => vi.fn());
const configEnv = vi.hoisted(() => ({
  appEnvironment: "production",
  rateLimitBackend: "postgres",
  upstash: {
    redisUrl: "https://redis.example.test",
    redisToken: "test-token",
  },
}));

vi.mock("@/database/redis", () => ({ default: {} }));
vi.mock("@/lib/config", () => ({ default: { env: configEnv } }));
vi.mock("@/lib/security/postgres-rate-limit", () => ({
  applyPostgresRateLimit,
}));

describe("distributed rate limiting", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubEnv("DISABLE_RATE_LIMIT", "false");
    configEnv.appEnvironment = "production";
    configEnv.upstash.redisUrl = "https://redis.example.test";
    configEnv.upstash.redisToken = "test-token";
    applyPostgresRateLimit.mockReset();
    applyPostgresRateLimit.mockResolvedValue({
      success: true,
      limit: 30,
      remaining: 29,
      reset: Date.now() + 60_000,
      pending: Promise.resolve(),
    });
  });

  it("passes the raw identity to the PostgreSQL backend for one stable hash", async () => {
    const { applyDistributedRateLimit } = await import("@/lib/ratelimit");

    await applyDistributedRateLimit({
      scope: "request:sensitive",
      identifier: "issuer:user@example.test",
      limit: 30,
    });

    expect(applyPostgresRateLimit).toHaveBeenCalledWith({
      scope: "request:sensitive",
      identifier: "issuer:user@example.test",
      limit: 30,
      windowSeconds: 60,
    });
  });

  it("does not implicitly bypass admission in the test environment", async () => {
    configEnv.appEnvironment = "test";
    configEnv.upstash.redisUrl = "";
    configEnv.upstash.redisToken = "";
    const { applyDistributedRateLimit } = await import("@/lib/ratelimit");

    await applyDistributedRateLimit({
      scope: "request:command",
      identifier: "test-principal",
      limit: 120,
    });

    expect(applyPostgresRateLimit).toHaveBeenCalledOnce();
  });
});
