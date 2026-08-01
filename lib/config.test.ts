import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const managedKeys = [
  "NODE_ENV",
  "NEXT_PHASE",
  "APP_ENV",
  "DATABASE_URL",
  "AUTH_SECRET",
  "NEXTAUTH_SECRET",
  "UPSTASH_REDIS_URL",
  "UPSTASH_REDIS_TOKEN",
  "TRUST_PROXY_HEADERS",
  "DISABLE_RATE_LIMIT",
  "ALLOW_PUBLIC_SIGNUP",
  "ENABLE_WORKFLOWS",
  "OIDC_ISSUER",
  "OIDC_CLIENT_ID",
  "OIDC_CLIENT_SECRET",
  "OIDC_ALLOWED_EMAIL_DOMAINS",
  "ENABLE_LOCAL_CREDENTIALS",
] as const;

const originalValues = new Map(
  managedKeys.map((key) => [key, process.env[key]]),
);

const setValidProductionEnvironment = () => {
  process.env.APP_ENV = "production";
  process.env.DATABASE_URL = "postgresql://example:example@localhost/example";
  process.env.AUTH_SECRET = "test-secret-with-sufficient-entropy";
  process.env.UPSTASH_REDIS_URL = "https://redis.example.test";
  process.env.UPSTASH_REDIS_TOKEN = "test-token";
  process.env.TRUST_PROXY_HEADERS = "true";
  process.env.DISABLE_RATE_LIMIT = "false";
  process.env.ALLOW_PUBLIC_SIGNUP = "false";
  process.env.ENABLE_WORKFLOWS = "false";
  process.env.OIDC_ISSUER = "https://identity.example.test/tenant";
  process.env.OIDC_CLIENT_ID = "library-bff";
  process.env.OIDC_CLIENT_SECRET = "test-client-secret";
  process.env.OIDC_ALLOWED_EMAIL_DOMAINS = "student.example.test";
  process.env.ENABLE_LOCAL_CREDENTIALS = "false";
};

describe.sequential("production configuration", () => {
  beforeEach(() => {
    vi.resetModules();
    setValidProductionEnvironment();
  });

  afterEach(() => {
    for (const key of managedKeys) {
      const original = originalValues.get(key);
      if (original === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = original;
      }
    }
    vi.resetModules();
  });

  it("accepts an explicit fail-closed production configuration", async () => {
    await expect(import("@/lib/config")).resolves.toBeDefined();
  });

  it("rejects an implicit deployment tier in a production runtime", async () => {
    process.env.NODE_ENV = "production";
    delete process.env.APP_ENV;

    await expect(import("@/lib/config")).rejects.toThrow(
      /APP_ENV must be explicitly set/,
    );
  });

  it.each(["development", "test"])(
    "rejects APP_ENV=%s in a production runtime",
    async (appEnvironment) => {
      process.env.NODE_ENV = "production";
      process.env.APP_ENV = appEnvironment;
      delete process.env.NEXT_PHASE;

      await expect(import("@/lib/config")).rejects.toThrow(
        /production runtime requires APP_ENV=staging or APP_ENV=production/,
      );
    },
  );

  it("permits an explicit local tier only during the Next production build phase", async () => {
    process.env.NODE_ENV = "production";
    process.env.APP_ENV = "development";
    process.env.NEXT_PHASE = "phase-production-build";

    await expect(import("@/lib/config")).resolves.toBeDefined();
  });

  it("rejects production without distributed rate-limit storage", async () => {
    delete process.env.UPSTASH_REDIS_URL;

    await expect(import("@/lib/config")).rejects.toThrow(/UPSTASH_REDIS_URL/);
  });

  it("rejects production security bypasses", async () => {
    process.env.DISABLE_RATE_LIMIT = "true";

    await expect(import("@/lib/config")).rejects.toThrow(
      /DISABLE_RATE_LIMIT is forbidden/,
    );
  });

  it("rejects production without complete institutional OIDC settings", async () => {
    delete process.env.OIDC_CLIENT_SECRET;

    await expect(import("@/lib/config")).rejects.toThrow(/OIDC_CLIENT_SECRET/);
  });

  it("rejects a non-HTTPS or non-exact institutional issuer", async () => {
    process.env.OIDC_ISSUER =
      "http://identity.example.test/tenant?not-exact=true";

    await expect(import("@/lib/config")).rejects.toThrow(
      /Invalid environment variables/,
    );
  });

  it("forbids the legacy credentials provider in production", async () => {
    process.env.ENABLE_LOCAL_CREDENTIALS = "true";

    await expect(import("@/lib/config")).rejects.toThrow(
      /ENABLE_LOCAL_CREDENTIALS is forbidden/,
    );
  });

  it("requires OIDC in staging too", async () => {
    process.env.APP_ENV = "staging";
    delete process.env.OIDC_ISSUER;

    await expect(import("@/lib/config")).rejects.toThrow(/OIDC_ISSUER/);
  });

  it("forbids the legacy credentials provider in staging too", async () => {
    process.env.APP_ENV = "staging";
    process.env.ENABLE_LOCAL_CREDENTIALS = "true";

    await expect(import("@/lib/config")).rejects.toThrow(
      /ENABLE_LOCAL_CREDENTIALS is forbidden/,
    );
  });

  it("keeps deterministic password login available in test by default", async () => {
    process.env.APP_ENV = "test";
    delete process.env.ENABLE_LOCAL_CREDENTIALS;

    const { default: config } = await import("@/lib/config");
    expect(config.env.localCredentialsEnabled).toBe(true);
  });
});
