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

/**
 * Application configuration schema using Zod for runtime validation.
 * This ensures that the application fails fast if critical environment
 * variables are missing or misconfigured.
 */
const envSchema = z.object({
  /** Base URL for the API (used in client-side requests). */
  apiEndpoint: z.string().url().default("http://localhost:3000"),
  /** Production-grade API endpoint (often the same as apiEndpoint). */
  prodApiEndpoint: z.string().url().default("http://localhost:3000"),
  
  /** ImageKit configuration for image hosting and optimization. */
  imagekit: z.object({
    publicKey: z.string().default(""),
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
    senderEmail: z.string().email("Brevo Sender Email must be a valid email").default("noreply@example.com"),
    senderName: z.string().default("Mundiapolis Library"),
  }),
  
  /** Resend configuration used as a fallback or secondary email provider. */
  resendToken: z.string().default(""),

  /** 
   * Global toggle for background workflows (onboarding, reminders). 
   * Useful for disabling automation in restricted local environments.
   */
  enableWorkflows: z.string().default("false").transform((v) => v === "true"),
});

/**
 * Maps environment variables to the structured schema.
 * Note: Only variables prefixed with NEXT_PUBLIC_ are accessible in the browser.
 */
const envData = {
  apiEndpoint: process.env.NEXT_PUBLIC_API_ENDPOINT,
  prodApiEndpoint: process.env.NEXT_PUBLIC_PROD_API_ENDPOINT,
  imagekit: {
    publicKey: process.env.NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY,
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

if (!parsedEnv.success) {
  console.error(
    "❌ Invalid environment variables:",
    JSON.stringify(parsedEnv.error.format(), null, 2)
  );
  
  // In production, fail hard on the server. Client bundles do not receive
  // server-only variables like DATABASE_URL.
  if (process.env.NODE_ENV === "production" && isServer) {
    throw new Error("Invalid environment variables. Check the logs for details.");
  }
}

/**
 * Exported application configuration.
 * Provides type-safe access to environment variables.
 */
const config = {
  env: parsedEnv.success
    ? parsedEnv.data
    : (scrubUndefined(envData) as unknown as z.infer<typeof envSchema>),
};

export default config;
