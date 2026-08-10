import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const admitRequest = vi.hoisted(() => vi.fn());

vi.mock("@/lib/security/request-admission", () => ({
  admitRequest,
  requiresRequestAdmission: (request: NextRequest) =>
    request.nextUrl.pathname.startsWith("/api/") ||
    !["GET", "HEAD", "OPTIONS"].includes(request.method),
}));

import { buildContentSecurityPolicy, middleware } from "./middleware";

const getNonce = (response: Awaited<ReturnType<typeof middleware>>) => {
  const policy = response.headers.get("content-security-policy") ?? "";
  const match = policy.match(/'nonce-([^']+)'/);
  return { nonce: match?.[1], policy };
};

describe("document security middleware", () => {
  beforeEach(() => {
    admitRequest.mockResolvedValue({
      budget: { scope: "request:read", limit: 300 },
      decision: {
        success: true,
        limit: 300,
        remaining: 299,
        reset: Date.now() + 60_000,
        pending: Promise.resolve(),
      },
    });
  });

  it("applies a unique production-safe script nonce to public documents", async () => {
    const first = await middleware(
      new NextRequest("https://library.example.edu/sign-in"),
    );
    const second = await middleware(
      new NextRequest("https://library.example.edu/sign-in"),
    );
    const firstPolicy = getNonce(first);
    const secondPolicy = getNonce(second);

    expect(first.status).toBe(200);
    expect(firstPolicy.nonce).toBeTruthy();
    expect(secondPolicy.nonce).toBeTruthy();
    expect(firstPolicy.nonce).not.toBe(secondPolicy.nonce);
    expect(firstPolicy.policy).toContain("'strict-dynamic'");
    expect(firstPolicy.policy).toContain("script-src-attr 'none'");
    expect(firstPolicy.policy).not.toMatch(
      /script-src[^;]*'unsafe-inline'/,
    );
    expect(firstPolicy.policy).not.toContain("'unsafe-eval'");
  });

  it("allows evaluation only in an explicitly development policy", () => {
    const developmentPolicy = buildContentSecurityPolicy("test-nonce", true);
    const productionPolicy = buildContentSecurityPolicy("test-nonce", false);

    expect(developmentPolicy).toMatch(/script-src[^;]*'unsafe-eval'/);
    expect(developmentPolicy).toContain("ws: wss:");
    expect(productionPolicy).not.toContain("'unsafe-eval'");
    expect(productionPolicy).not.toContain("ws: wss:");
    expect(productionPolicy).toContain("upgrade-insecure-requests");
  });

  it("redirects requests with no session cookie to sign in", async () => {
    const response = await middleware(
      new NextRequest("https://library.example.edu/admin/users?page=2"),
    );

    expect(response.status).toBe(307);
    const location = new URL(response.headers.get("location") ?? "");
    expect(location.origin).toBe("https://library.example.edu");
    expect(location.pathname).toBe("/sign-in");
    expect(location.searchParams.get("callbackUrl")).toBe(
      "/admin/users?page=2",
    );
    expect(getNonce(response).nonce).toBeTruthy();
  });

  it.each(["authjs.session-token", "__Secure-authjs.session-token"])(
    "passes %s to the authoritative server guard",
    async (cookieName) => {
      const request = new NextRequest("https://library.example.edu/admin");
      request.cookies.set(cookieName, "opaque-untrusted-cookie");

      const response = await middleware(request);

      expect(response.status).toBe(200);
      expect(response.headers.get("x-middleware-next")).toBe("1");
    },
  );

  it("applies distributed admission to every API route", async () => {
    const response = await middleware(
      new NextRequest("https://library.example.edu/api/books"),
    );

    expect(response.status).toBe(200);
    expect(admitRequest).toHaveBeenCalledOnce();
    expect(response.headers.get("RateLimit-Limit")).toBe("300");
    expect(response.headers.get("RateLimit-Remaining")).toBe("299");
    expect(Number(response.headers.get("RateLimit-Reset"))).toBeGreaterThan(0);
    expect(Number(response.headers.get("RateLimit-Reset"))).toBeLessThanOrEqual(60);
  });

  it("returns a bounded problem response when a request exceeds its budget", async () => {
    admitRequest.mockResolvedValueOnce({
      budget: { scope: "request:command", limit: 120 },
      decision: {
        success: false,
        limit: 120,
        remaining: 0,
        reset: Date.now() + 30_000,
        pending: Promise.resolve(),
      },
    });

    const response = await middleware(
      new NextRequest("https://library.example.edu/api/reviews/book-id", {
        method: "POST",
      }),
    );

    expect(response.status).toBe(429);
    expect(response.headers.get("content-type")).toContain(
      "application/problem+json",
    );
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(Number(response.headers.get("retry-after"))).toBeGreaterThan(0);
    expect(response.headers.get("RateLimit-Reset")).toBe(
      response.headers.get("retry-after"),
    );
    await expect(response.json()).resolves.toMatchObject({
      code: "rate_limit_exceeded",
      status: 429,
    });
  });

  it("fails closed with 503 when request admission is unavailable", async () => {
    admitRequest.mockResolvedValueOnce({
      budget: { scope: "request:sensitive", limit: 30 },
      decision: {
        success: false,
        unavailable: true,
        limit: 30,
        remaining: 0,
        reset: Date.now() + 60_000,
        pending: Promise.resolve(),
      },
    });

    const response = await middleware(
      new NextRequest("https://library.example.edu/api/uploads", {
        method: "POST",
      }),
    );

    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({
      code: "rate_limit_unavailable",
      status: 503,
    });
  });
});
