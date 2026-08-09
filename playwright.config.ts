import { defineConfig, devices } from "@playwright/test";

const PORT = Number(process.env.E2E_PORT || 3100);
const baseURL = process.env.E2E_BASE_URL || `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL,
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "mobile-chromium",
      use: {
        ...devices["iPhone 13"],
        browserName: "chromium",
        viewport: { width: 390, height: 844 },
      },
    },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `npm run dev -- --port ${PORT}`,
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        env: {
          DATABASE_URL:
            process.env.DATABASE_URL ||
            "postgresql://build:build@127.0.0.1:5432/builddb",
          NEXTAUTH_SECRET:
            process.env.NEXTAUTH_SECRET || "ci-e2e-secret-ci-e2e-secret",
          AUTH_SECRET: process.env.AUTH_SECRET || "ci-e2e-secret-ci-e2e-secret",
          NEXTAUTH_URL: baseURL,
          NEXT_PUBLIC_API_ENDPOINT: baseURL,
          NEXT_PUBLIC_PROD_API_ENDPOINT: baseURL,
          NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT:
            process.env.NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT ||
            "https://example.com",
          IMAGEKIT_PRIVATE_KEY:
            process.env.IMAGEKIT_PRIVATE_KEY || "private-key",
          // Browser tests must not inherit deployment credentials or contact
          // external Redis/QStash services from a maintainer's shell.
          UPSTASH_REDIS_URL: process.env.E2E_UPSTASH_REDIS_URL || "",
          UPSTASH_REDIS_TOKEN: process.env.E2E_UPSTASH_REDIS_TOKEN || "",
          QSTASH_URL: "",
          QSTASH_TOKEN: "",
          ENABLE_WORKFLOWS: "false",
        },
      },
});
