import { z } from "zod";

/**
 * Application configuration schema.
 * Validates environment variables at runtime.
 */
const envSchema = z.object({
  // API Endpoints
  apiEndpoint: z.string().url().default("http://localhost:3000"),
  prodApiEndpoint: z.string().url().default("http://localhost:3000"),
  
  // ImageKit
  imagekit: z.object({
    publicKey: z.string().default(""),
    urlEndpoint: z.union([z.string().url(), z.literal("")]).default(""),
    privateKey: z.string().default(""),
  }),
  
  // Database
  databaseUrl: z.string().default(""),
  
  // Upstash (Redis + QStash)
  upstash: z.object({
    redisUrl: z.union([z.string().url(), z.literal("")]).default(""),
    redisToken: z.string().default(""),
    qstashUrl: z.union([z.string().url(), z.literal("")]).default(""),
    qstashToken: z.string().default(""),
  }),
  
  // Email (Brevo)
  brevo: z.object({
    apiKey: z.string().default(""),
    senderEmail: z.string().email("Brevo Sender Email must be a valid email").default("noreply@example.com"),
    senderName: z.string().default("Mundiapolis Library"),
  }),
  
  // Email (Resend)
  resendToken: z.string().default(""),

  // Feature Flags
  enableWorkflows: z.string().default("false").transform((v) => v === "true"),
});

// Map process.env to the schema structure
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
  },
  brevo: {
    apiKey: process.env.BREVO_API_KEY,
    senderEmail: process.env.BREVO_SENDER_EMAIL,
    senderName: process.env.BREVO_SENDER_NAME,
  },
  resendToken: process.env.RESEND_TOKEN,
  enableWorkflows: process.env.ENABLE_WORKFLOWS,
};

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
 * Normalized config with validated env values.
 */
const config = {
  env: parsedEnv.success
    ? parsedEnv.data
    : (scrubUndefined(envData) as unknown as z.infer<typeof envSchema>),
};

export default config;
