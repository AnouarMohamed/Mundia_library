import { z } from "zod";

/**
 * Application Configuration Schema
 * 
 * Validates environment variables at runtime to ensure all required 
 * services are correctly configured.
 */
const envSchema = z.object({
  // API Endpoints
  apiEndpoint: z.string().url().default("http://localhost:3000"),
  prodApiEndpoint: z.string().url().default("http://localhost:3000"),
  
  // ImageKit
  imagekit: z.object({
    publicKey: z.string().min(1, "ImageKit Public Key is required"),
    urlEndpoint: z.string().url("ImageKit URL Endpoint must be a valid URL"),
    privateKey: z.string().min(1, "ImageKit Private Key is required"),
  }),
  
  // Database
  databaseUrl: z.string().min(1, "DATABASE_URL is required"),
  
  // Upstash (Redis + QStash)
  upstash: z.object({
    redisUrl: z.string().url("Upstash Redis URL must be a valid URL"),
    redisToken: z.string().min(1, "Upstash Redis Token is required"),
    qstashUrl: z.string().url("QStash URL must be a valid URL"),
    qstashToken: z.string().min(1, "QStash Token is required"),
  }),
  
  // Email (Brevo)
  brevo: z.object({
    apiKey: z.string().min(1, "Brevo API Key is required"),
    senderEmail: z.string().email("Brevo Sender Email must be a valid email"),
    senderName: z.string().default("Mundiapolis Library"),
  }),
  
  // Email (Resend)
  resendToken: z.string().min(1, "Resend Token is required"),

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

// Validate environment variables
const parsedEnv = envSchema.safeParse(envData);

if (!parsedEnv.success) {
  console.error(
    "❌ Invalid environment variables:",
    JSON.stringify(parsedEnv.error.format(), null, 2)
  );
  
  // In production, we want to fail hard if config is missing
  if (process.env.NODE_ENV === "production") {
    throw new Error("Invalid environment variables. Check the logs for details.");
  }
}

const config = {
  env: parsedEnv.success
    ? parsedEnv.data
    : (envData as unknown as z.infer<typeof envSchema>),
};

export default config;
