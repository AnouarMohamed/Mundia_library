/**
 * Next.js Middleware
 *
 * This middleware runs only for admin pages.
 *
 * IMPORTANT: Middleware runs in Edge Runtime, NOT Node.js runtime!
 *
 * Edge Runtime Limitations:
 * - Cannot use Node.js modules (fs, crypto, pg, etc.)
 * - Limited API surface (no database connections)
 * - Fast execution but limited capabilities
 *
 * What this middleware does:
 * - Uses NextAuth's auth() function to check authentication for /admin routes
 *
 * Note:
 * - Root app routes are protected by route-group layouts and page-level guards.
 * - API routes are protected by route handlers/actions.
 *
 * Why export auth as middleware?
 * - NextAuth provides built-in middleware functionality
 * - Handles admin route session validation at the edge
 *
 * Note: The auth.ts file uses lazy imports to avoid loading database
 * modules in Edge runtime. Database operations only happen in Node.js runtime
 * (API routes, Server Components, Server Actions).
 */
export { auth as middleware } from "@/auth";

// Keep middleware narrow to reduce edge auth checks on normal app navigation.
export const config = {
  matcher: ["/admin/:path*"],
};
