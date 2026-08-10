import type { NextRequest } from "next/server";

import { applyDistributedRateLimit } from "@/lib/ratelimit";
import { getClientIpFromHeaders } from "@/lib/security/api-request";

export type RequestBudget = {
  scope: "request:read" | "request:command" | "request:sensitive";
  limit: number;
};

const SENSITIVE_PATH_SEGMENTS = [
  "/api/auth/",
  "/api/uploads",
  "/export",
  "/generate-",
  "/identity-card",
  "/refresh-",
  "/send-",
  "/update-",
] as const;

export const isHealthProbe = (pathname: string) =>
  pathname === "/api/health" || pathname === "/api/health/live";

export const requiresRequestAdmission = (request: NextRequest) => {
  const { pathname } = request.nextUrl;
  if (isHealthProbe(pathname) || request.method === "OPTIONS") return false;
  if (pathname.startsWith("/api/")) return true;

  // Server Actions and other mutating form requests use application page
  // routes rather than /api. Admit them at the same shared boundary.
  return request.method !== "GET" && request.method !== "HEAD";
};

export const classifyRequestBudget = (request: NextRequest): RequestBudget => {
  const { pathname } = request.nextUrl;
  if (
    SENSITIVE_PATH_SEGMENTS.some((segment) => pathname.includes(segment)) ||
    pathname === "/sign-in" ||
    pathname === "/sign-up"
  ) {
    return { scope: "request:sensitive", limit: 30 };
  }
  if (request.method === "GET" || request.method === "HEAD") {
    return { scope: "request:read", limit: 300 };
  }
  return { scope: "request:command", limit: 120 };
};

export const admitRequest = async (request: NextRequest) => {
  const budget = classifyRequestBudget(request);
  const decision = await applyDistributedRateLimit({
    scope: budget.scope,
    identifier: getClientIpFromHeaders(request.headers),
    limit: budget.limit,
  });
  return { budget, decision };
};
