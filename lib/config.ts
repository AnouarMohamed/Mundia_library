/**
 * Application Configuration Module
 *
 * This module centralizes all environment-based configuration for the application.
 * It uses Zod for runtime validation, ensuring that missing or invalid environment
 * variables are caught early in the development and deployment lifecycle.
 *
 * Key configuration areas:
 * - Database & Caching (PostgreSQL, Upstash Redis)
 * - File Storage (ImageKit)
 * - Communication (Brevo, Resend)
 * - Background Workflows (QStash)
 */

import { z } from "zod";

const optionalBooleanString = z
  .enum(["true", "false"])
  .optional()
  .transform((value) => (value === undefined ? undefined : value === "true"));

const exactHttpsIssuer = z
  .string()
  .default("")
  .refine((value) => {
    if (!value) return true;
    if (value.trim() !== value) return false;

    try {
      const issuer = new URL(value);
      return (
        issuer.protocol === "https:" &&
        !issuer.username &&
        !issuer.password &&
        !issuer.search &&
        !issuer.hash
      );
    } catch {
      return false;
    }
  }, "OIDC issuer must be an exact HTTPS URL without credentials, query, or fragment");

const optionalOpaqueSetting = (maximumLength: number, label: string) =>
  z
    .string()
    .max(maximumLength)
    .default("")
    .refine(
      (value) => value === "" || /\S/.test(value),
      `${label} must not contain only whitespace`,
    );

const oidcEmailDomains = z
  .string()
  .default("")
  .transform((value) =>
    value
      .split(",")
      .map((domain) => domain.trim().toLowerCase())
      .filter(Boolean),
  )
  .refine(
    (domains) =>
      domains.every(
        (domain) =>
          domain.length <= 253 &&
          /^(?!-)(?:[a-z0-9-]+\.)*[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(
            domain,
          ),
      ),
    "OIDC allowed email domains must be comma-separated DNS names",
  );

/**
 * Application configuration schema using Zod for runtime validation.
 * This ensures that the application fails fast if critical environment
 * variables are missing or misconfigured.
 */
const envSchema = z.object({
  /** Explicit deployment tier; production enables fail-closed validation. */
  appEnvironment: z
    .enum(["development", "test", "staging", "production"])
    .default("development"),

  /** Base URL for the API (used in client-side requests). */
  apiEndpoint: z.string().url().default("http://localhost:3000"),
  /** Production-grade API endpoint (often the same as apiEndpoint). */
  prodApiEndpoint: z.string().url().default("http://localhost:3000"),

  /** ImageKit configuration for image hosting and optimization. */
  imagekit: z.object({
    urlEndpoint: z.union([z.string().url(), z.literal("")]).default(""),
    privateKey: z.string().default(""),
  }),

  /** Primary database connection string (PostgreSQL). */
  databaseUrl: z.string().default(""),

  /** Upstash services for caching (Redis) and background task orchestration (QStash). */
  upstash: z.object({
    redisUrl: z.union([z.string().url(), z.literal("")]).default(""),
    redisToken: z.string().default(""),
    qstashUrl: z.union([z.string().url(), z.literal("")]).default(""),
    qstashToken: z.string().default(""),
    qstashCurrentSigningKey: z.string().default(""),
    qstashNextSigningKey: z.string().default(""),
  }),

  /** Brevo (formerly Sendinblue) configuration for transactional emails. */
  brevo: z.object({
    apiKey: z.string().default(""),
    senderEmail: z
      .string()
      .email("Brevo Sender Email must be a valid email")
      .default("noreply@example.com"),
    senderName: z.string().default("Mundiapolis Library"),
  }),

  /** Resend configuration used as a fallback or secondary email provider. */
  resendToken: z.string().default(""),

  /**
   * Global toggle for background workflows (onboarding, reminders).
   * Useful for disabling automation in restricted local environments.
   */
  enableWorkflows: z
    .string()
    .default("false")
    .transform((v) => v === "true"),

  /**
   * Legacy credential signup is disabled by default. Production identity must
   * use the institutional OIDC provider or a verified invitation flow.
   */
  allowPublicSignup: z
    .string()
    .default("false")
    .transform((v) => v === "true"),

  /**
   * Proxy forwarding headers are ignored unless the deployment explicitly
   * confirms that its ingress strips and rewrites them.
   */
  trustProxyHeaders: z
    .string()
    .default("false")
    .transform((v) => v === "true"),

  /**
   * Institutional identity provider. The issuer string is retained exactly:
   * it is never lowercased, trimmed, or otherwise canonicalized.
   */
  oidc: z.object({
    issuer: exactHttpsIssuer,
    clientId: optionalOpaqueSetting(512, "OIDC client ID"),
    clientSecret: optionalOpaqueSetting(4096, "OIDC client secret"),
    allowedEmailDomains: oidcEmailDomains,
  }),

  /**
   * Password sign-in is a local compatibility path. Undefined means enabled
   * in development/test only; it can never enable credentials in a protected
   * deployment tier.
   */
  enableLocalCredentials: optionalBooleanString,
});

/**
 * Maps environment variables to the structured schema.
 * Note: Only variables prefixed with NEXT_PUBLIC_ are accessible in the browser.
 */
const envData = {
  appEnvironment: process.env.APP_ENV,
  apiEndpoint: process.env.NEXT_PUBLIC_API_ENDPOINT,
  prodApiEndpoint: process.env.NEXT_PUBLIC_PROD_API_ENDPOINT,
  imagekit: {
    urlEndpoint: process.env.NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT,
    privateKey: process.env.IMAGEKIT_PRIVATE_KEY,
  },
  databaseUrl: process.env.DATABASE_URL,
  upstash: {
    redisUrl: process.env.UPSTASH_REDIS_URL,
    redisToken: process.env.UPSTASH_REDIS_TOKEN,
    qstashUrl: process.env.QSTASH_URL,
    qstashToken: process.env.QSTASH_TOKEN,
    qstashCurrentSigningKey: process.env.QSTASH_CURRENT_SIGNING_KEY,
    qstashNextSigningKey: process.env.QSTASH_NEXT_SIGNING_KEY,
  },
  brevo: {
    apiKey: process.env.BREVO_API_KEY,
    senderEmail: process.env.BREVO_SENDER_EMAIL,
    senderName: process.env.BREVO_SENDER_NAME,
  },
  resendToken: process.env.RESEND_TOKEN,
  enableWorkflows: process.env.ENABLE_WORKFLOWS,
  allowPublicSignup: process.env.ALLOW_PUBLIC_SIGNUP,
  trustProxyHeaders: process.env.TRUST_PROXY_HEADERS,
  oidc: {
    issuer: process.env.OIDC_ISSUER,
    clientId: process.env.OIDC_CLIENT_ID,
    clientSecret: process.env.OIDC_CLIENT_SECRET,
    allowedEmailDomains: process.env.OIDC_ALLOWED_EMAIL_DOMAINS,
  },
  enableLocalCredentials: process.env.ENABLE_LOCAL_CREDENTIALS,
};

/**
 * Recursively removes undefined values from an object to prevent Zod validation
 * from failing on missing optional fields that have defaults.
 */
const scrubUndefined = <T extends Record<string, unknown>>(value: T): T => {
  for (const key of Object.keys(value)) {
    const item = value[key];

    if (item && typeof item === "object" && !Array.isArray(item)) {
      scrubUndefined(item as Record<string, unknown>);
    } else if (item === undefined) {
      delete value[key];
    }
  }

  return value;
};

// Validate environment variables.
const parsedEnv = envSchema.safeParse(envData);
const isServer = typeof window === "undefined";

if (isServer && process.env.NODE_ENV === "production" && !process.env.APP_ENV) {
  throw new Error(
    "APP_ENV must be explicitly set for a production runtime or production build",
  );
}

if (!parsedEnv.success) {
  console.error(
    "❌ Invalid environment variables:",
    JSON.stringify(parsedEnv.error.format(), null, 2),
  );

  // In production, fail hard on the server. Client bundles do not receive
  // server-only variables like DATABASE_URL.
  if (
    isServer &&
    (process.env.NODE_ENV === "production" ||
      process.env.APP_ENV === "staging" ||
      process.env.APP_ENV === "production")
  ) {
    throw new Error(
      "Invalid environment variables. Check the logs for details.",
    );
  }
}

if (parsedEnv.success && isServer) {
  const protectedIdentityTier = ["staging", "production"].includes(
    parsedEnv.data.appEnvironment,
  );
  const oidcValuesPresent = [
    parsedEnv.data.oidc.issuer,
    parsedEnv.data.oidc.clientId,
    parsedEnv.data.oidc.clientSecret,
    ...parsedEnv.data.oidc.allowedEmailDomains,
  ].some(Boolean);
  const missingOidc: string[] = [];
  if (!parsedEnv.data.oidc.issuer) missingOidc.push("OIDC_ISSUER");
  if (!parsedEnv.data.oidc.clientId) missingOidc.push("OIDC_CLIENT_ID");
  if (!parsedEnv.data.oidc.clientSecret) missingOidc.push("OIDC_CLIENT_SECRET");
  if (parsedEnv.data.oidc.allowedEmailDomains.length === 0) {
    missingOidc.push("OIDC_ALLOWED_EMAIL_DOMAINS");
  }

  if ((protectedIdentityTier || oidcValuesPresent) && missingOidc.length > 0) {
    throw new Error(
      `Missing institutional OIDC configuration: ${missingOidc.join(", ")}`,
    );
  }

  if (protectedIdentityTier && parsedEnv.data.enableLocalCredentials === true) {
    throw new Error(
      "ENABLE_LOCAL_CREDENTIALS is forbidden when APP_ENV is staging or production",
    );
  }
  if (protectedIdentityTier && parsedEnv.data.allowPublicSignup) {
    throw new Error(
      "ALLOW_PUBLIC_SIGNUP is forbidden when APP_ENV is staging or production",
    );
  }
}

if (
  parsedEnv.success &&
  isServer &&
  parsedEnv.data.appEnvironment === "production"
) {
  const missing: string[] = [];
  if (!parsedEnv.data.databaseUrl) missing.push("DATABASE_URL");
  if (!(process.env.AUTH_SECRET || process.env.NEXTAUTH_SECRET)) {
    missing.push("AUTH_SECRET");
  }
  if (!parsedEnv.data.upstash.redisUrl) missing.push("UPSTASH_REDIS_URL");
  if (!parsedEnv.data.upstash.redisToken) missing.push("UPSTASH_REDIS_TOKEN");
  if (!parsedEnv.data.trustProxyHeaders)
    missing.push("TRUST_PROXY_HEADERS=true");

  if (parsedEnv.data.enableWorkflows) {
    if (!parsedEnv.data.upstash.qstashToken) missing.push("QSTASH_TOKEN");
    if (!parsedEnv.data.upstash.qstashCurrentSigningKey) {
      missing.push("QSTASH_CURRENT_SIGNING_KEY");
    }
    if (!parsedEnv.data.upstash.qstashNextSigningKey) {
      missing.push("QSTASH_NEXT_SIGNING_KEY");
    }
  }

  if (process.env.DISABLE_RATE_LIMIT === "true") {
    throw new Error("DISABLE_RATE_LIMIT is forbidden when APP_ENV=production");
  }
  if (parsedEnv.data.allowPublicSignup) {
    throw new Error("ALLOW_PUBLIC_SIGNUP is forbidden when APP_ENV=production");
  }
  if (missing.length > 0) {
    throw new Error(`Missing production configuration: ${missing.join(", ")}`);
  }
}

/**
 * Exported application configuration.
 * Provides type-safe access to environment variables.
 */
const config = {
  env: (() => {
    const env = parsedEnv.success
      ? parsedEnv.data
      : (scrubUndefined(envData) as unknown as z.infer<typeof envSchema>);
    const localTier = ["development", "test"].includes(env.appEnvironment);
    const oidcEnabled = Boolean(
      env.oidc?.issuer &&
      env.oidc.clientId &&
      env.oidc.clientSecret &&
      env.oidc.allowedEmailDomains.length > 0,
    );

    return {
      ...env,
      oidc: {
        ...env.oidc,
        enabled: oidcEnabled,
      },
      localCredentialsEnabled:
        localTier && env.enableLocalCredentials !== false,
    };
  })(),
};

export default config;
