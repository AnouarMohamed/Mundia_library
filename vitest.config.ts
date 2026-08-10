import { defineConfig } from "vitest/config";
import path from "path";

export default defineConfig({
  test: {
    environment: "node",
    env: {
      // Exercise test-tier fail-closed behavior by default. Individual tests
      // may explicitly opt out only when their scenario requires it.
      APP_ENV: "test",
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
