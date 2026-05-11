import { handlers } from "@/auth";

/**
 * Force the NextAuth route handlers to run in the Node.js runtime.
 */
export const runtime = "nodejs";

/**
 * NextAuth route handlers (GET/POST) used by Next.js App Router.
 */
export const { GET, POST } = handlers;
