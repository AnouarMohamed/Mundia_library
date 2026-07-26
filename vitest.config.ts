import { defineConfig } from "vitest/config";
import path from "path";

export default defineConfig({
  test: {
    environment: "node",
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
