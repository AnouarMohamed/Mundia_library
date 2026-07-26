import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { buildContentSecurityPolicy, middleware } from "./middleware";

const getNonce = (response: ReturnType<typeof middleware>) => {
  const policy = response.headers.get("content-security-policy") ?? "";
  const match = policy.match(/'nonce-([^']+)'/);
  return { nonce: match?.[1], policy };
};

describe("document security middleware", () => {
  it("applies a unique production-safe script nonce to public documents", () => {
    const first = middleware(
      new NextRequest("https://library.example.edu/sign-in"),
    );
    const second = middleware(
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

  it("redirects requests with no session cookie to sign in", () => {
    const response = middleware(
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
    (cookieName) => {
      const request = new NextRequest("https://library.example.edu/admin");
      request.cookies.set(cookieName, "opaque-untrusted-cookie");

      const response = middleware(request);

      expect(response.status).toBe(200);
      expect(response.headers.get("x-middleware-next")).toBe("1");
    },
  );
});
