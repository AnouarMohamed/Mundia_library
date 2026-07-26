/**
 * Next.js Edge Middleware
 *
 * This middleware provides a per-request Content Security Policy nonce for
 * rendered documents and a fast session-cookie presence prefilter for
 * sensitive routes. Authorization remains authoritative in the server layout,
 * which validates the session and current database state.
 *
 * @module middleware
 */

import { NextRequest, NextResponse } from "next/server";

const sessionCookieNames = [
  "__Secure-authjs.session-token",
  "authjs.session-token",
] as const;

const createNonce = () => btoa(crypto.randomUUID());

export const buildContentSecurityPolicy = (
  nonce: string,
  isDevelopment = process.env.NODE_ENV === "development",
) =>
  [
    "default-src 'self'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
    "frame-src 'none'",
    "object-src 'none'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${isDevelopment ? " 'unsafe-eval'" : ""}`,
    "script-src-attr 'none'",
    // React components and charting controls still emit bounded style
    // attributes. Script execution is nonce-restricted; eliminating this
    // remaining style exception is tracked as a separate verified migration.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https://ik.imagekit.io https://m.media-amazon.com https://placehold.co",
    "media-src 'self' data: blob: https://ik.imagekit.io",
    "font-src 'self' data:",
    `connect-src 'self' https://*.upstash.io https://*.imagekit.io https://ik.imagekit.io${isDevelopment ? " ws: wss:" : ""}`,
    "worker-src 'self' blob:",
    "manifest-src 'self'",
    "form-action 'self'",
    ...(isDevelopment ? [] : ["upgrade-insecure-requests"]),
  ].join("; ");

const applyContentSecurityPolicy = (
  response: NextResponse,
  contentSecurityPolicy: string,
) => {
  response.headers.set("Content-Security-Policy", contentSecurityPolicy);
  return response;
};

/**
 * Middleware function that handles request filtering and redirection.
 *
 * Redirects obviously unauthenticated `/admin` requests before they reach the
 * origin. Possessing a cookie is not treated as proof of authentication or
 * authorization; `app/admin/layout.tsx` always performs the real guard.
 *
 * @param {NextRequest} request - The incoming Next.js request object
 * @returns {NextResponse} The response (either a redirect or a continuation)
 */
export function middleware(request: NextRequest) {
  const nonce = createNonce();
  const contentSecurityPolicy = buildContentSecurityPolicy(nonce);
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", contentSecurityPolicy);

  const hasSessionCookie = sessionCookieNames.some((name) =>
    Boolean(request.cookies.get(name)?.value),
  );

  if (request.nextUrl.pathname.startsWith("/admin") && !hasSessionCookie) {
    const signInUrl = new URL("/sign-in", request.url);
    signInUrl.searchParams.set(
      "callbackUrl",
      `${request.nextUrl.pathname}${request.nextUrl.search}`,
    );
    return applyContentSecurityPolicy(
      NextResponse.redirect(signInUrl),
      contentSecurityPolicy,
    );
  }

  return applyContentSecurityPolicy(
    NextResponse.next({
      request: {
        headers: requestHeaders,
      },
    }),
    contentSecurityPolicy,
  );
}

/**
 * Middleware Configuration
 *
 * Generates nonces only for document requests. API routes and immutable assets
 * do not render executable HTML and keep the static security headers configured
 * by Next.js.
 */
export const config = {
  matcher: [
    {
      source:
        "/((?!api|_next/static|_next/image|favicon.ico|fonts/|icons/|images/).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
