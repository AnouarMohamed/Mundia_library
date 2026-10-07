import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  root: import.meta.dirname,
  publicDir: false,
  plugins: [
    react(),
    {
      name: "mundia-release-metadata",
      generateBundle() {
        this.emitFile({
          type: "asset",
          fileName: "build-metadata.json",
          source: `${JSON.stringify(
            {
              schemaVersion: 1,
              featureFlags: {
                learningResources: process.env.VITE_ENABLE_LEARNING_RESOURCES === "true",
                memberProfile: process.env.VITE_ENABLE_MEMBER_PROFILE === "true",
                circulationSelfService: process.env.VITE_ENABLE_CIRCULATION_SELF_SERVICE === "true",
                notifications: process.env.VITE_ENABLE_NOTIFICATIONS === "true",
              },
            },
            null,
            2,
          )}\n`,
        });
      },
    },
  ],
  build: {
    outDir: "../dist/web-spa",
    emptyOutDir: true,
    sourcemap: false,
    target: "es2022",
  },
  server: {
    port: 4173,
    strictPort: true,
    proxy: {
      "/api": "http://localhost:8080",
      "/oauth2": "http://localhost:8080",
      "/login": "http://localhost:8080",
    },
  },
});
