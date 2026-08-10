import { defineConfig } from "vitest/config";
import path from "path";

export default defineConfig({
  test: {
    environment: "node",
    env: {
      // Tests that do not provision a distributed backend must opt out
      // explicitly; APP_ENV=test no longer disables admission implicitly.
      DISABLE_RATE_LIMIT: "true",
    },
    // Nested services and migration utilities are independent packages with
    // their own runners and lockfiles. Keep the web suite deterministic even
    // when their dependencies have been installed locally.
    exclude: [
      "**/node_modules/**",
      "tests/e2e/**",
      "services/**",
      "tools/**",
      "platform/**",
    ],
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./"),
    },
  },
});
