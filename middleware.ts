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
 * - Reads the JWT token directly to check authentication for /admin routes
 *
 * Note:
 * - Root app routes are protected by route-group layouts and page-level guards.
 * - API routes are protected by route handlers/actions.
 *
 * Why not import auth.ts here?
 * - auth.ts configures credentials login and lazy database access
 * - Next.js still traces those imports for the Edge middleware bundle
 * - Reading the JWT here keeps MySQL/Node-only code out of Edge runtime
 */
import { NextRequest, NextResponse } from "next/server";
import { getToken } from "next-auth/jwt";

export async function middleware(request: NextRequest) {
  const token = await getToken({
    req: request,
    secret: process.env.NEXTAUTH_SECRET,
  });

  if (!token) {
    const signInUrl = new URL("/sign-in", request.url);
    signInUrl.searchParams.set("callbackUrl", request.nextUrl.href);
    return NextResponse.redirect(signInUrl);
  }

  if (token.role !== "ADMIN") {
    return NextResponse.redirect(new URL("/", request.url));
  }

  return NextResponse.next();
}

// Keep middleware narrow to reduce edge auth checks on normal app navigation.
export const config = {
  matcher: ["/admin/:path*"],
};
