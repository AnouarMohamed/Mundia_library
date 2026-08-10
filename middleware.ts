/**
 * Next.js request-boundary middleware
 *
 * This middleware provides a per-request Content Security Policy nonce for
 * rendered documents, distributed admission control for API and mutation
 * requests, and a fast session-cookie presence prefilter for sensitive routes.
 * Authorization remains authoritative in the server layout, which validates
 * the session and current database state.
 *
 * @module middleware
 */

import { NextRequest, NextResponse } from "next/server";

import {
  admitRequest,
  requiresRequestAdmission,
} from "@/lib/security/request-admission";

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

type Admission = Awaited<ReturnType<typeof admitRequest>>;

const applyRateLimitHeaders = <T extends NextResponse>(
  response: T,
  admission: Admission | undefined,
) => {
  if (!admission) return response;
  response.headers.set(
    "RateLimit-Limit",
    admission.decision.limit.toString(),
  );
  response.headers.set(
    "RateLimit-Remaining",
    admission.decision.remaining.toString(),
  );
  response.headers.set(
    "RateLimit-Reset",
    Math.ceil(admission.decision.reset / 1000).toString(),
  );
  return response;
};

const rejectedAdmissionResponse = (admission: Admission) => {
  const unavailable = admission.decision.unavailable === true;
  const status = unavailable ? 503 : 429;
  const retryAfter = Math.max(
    1,
    Math.ceil((admission.decision.reset - Date.now()) / 1000),
  );
  const response = NextResponse.json(
    {
      type: unavailable
        ? "urn:mundia:error:rate_limit_unavailable"
        : "urn:mundia:error:rate_limit_exceeded",
      title: unavailable ? "Service Unavailable" : "Too Many Requests",
      status,
      detail: unavailable
        ? "Request admission is temporarily unavailable"
        : "Too many requests; retry after the current window resets",
      code: unavailable ? "rate_limit_unavailable" : "rate_limit_exceeded",
    },
    {
      status,
      headers: {
        "Cache-Control": "no-store",
        "Content-Type": "application/problem+json",
        "Retry-After": retryAfter.toString(),
      },
    },
  );
  return applyRateLimitHeaders(response, admission);
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
export async function middleware(request: NextRequest) {
  const admission = requiresRequestAdmission(request)
    ? await admitRequest(request)
    : undefined;
  if (admission && !admission.decision.success) {
    return rejectedAdmissionResponse(admission);
  }

  if (request.nextUrl.pathname.startsWith("/api/")) {
    return applyRateLimitHeaders(NextResponse.next(), admission);
  }

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
    return applyRateLimitHeaders(
      applyContentSecurityPolicy(
        NextResponse.redirect(signInUrl),
        contentSecurityPolicy,
      ),
      admission,
    );
  }

  return applyRateLimitHeaders(
    applyContentSecurityPolicy(
      NextResponse.next({
        request: {
          headers: requestHeaders,
        },
      }),
      contentSecurityPolicy,
    ),
    admission,
  );
}

/**
 * Middleware Configuration
 *
 * Runs in the stable Node.js middleware runtime so the deployment may use its
 * configured PostgreSQL fallback when Redis is not selected. Nonces are still
 * generated only for documents; API responses retain the static headers from
 * Next.js and receive rate-limit metadata here.
 */
export const config = {
  runtime: "nodejs",
  matcher: [
    "/api/:path*",
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
