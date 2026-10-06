import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  root: import.meta.dirname,
  publicDir: false,
  plugins: [react()],
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
