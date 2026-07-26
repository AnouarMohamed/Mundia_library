/**
 * NextAuth Authentication API Route
 * 
 * This route handles all authentication-related requests (sign-in, sign-out, session retrieval, etc.).
 * It uses the catch-all dynamic segment [...nextauth] to process multiple sub-routes.
 * 
 * @module app/api/auth/[...nextauth]/route
 */

import { handlers } from "@/auth";

/**
 * Force the NextAuth route handlers to run in the Node.js runtime.
 * This is required because the auth configuration (auth.ts) may use Node-only modules
 * like database adapters or certain cryptographic libraries.
 */
export const runtime = "nodejs";

/**
 * NextAuth route handlers (GET/POST).
 * 
 * GET: Handles session retrieval and social login callbacks.
 * POST: Handles credential submission and CSRF token management.
 */
export const { GET, POST } = handlers;
