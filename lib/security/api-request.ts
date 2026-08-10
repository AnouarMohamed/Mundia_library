import { headers } from "next/headers";

import ratelimit from "@/lib/ratelimit";
import config from "@/lib/config";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Validate UUID route parameters before they reach the database layer.
 */
export const isUuid = (value: string) => UUID_PATTERN.test(value);

/**
 * Normalize bounded text query params before using them in SQL or cache keys.
 */
export const normalizeTextParam = (value: string | null, maxLength: number) =>
  (value ?? "").trim().slice(0, maxLength);

/**
 * Use the first forwarded IP when proxies append a chain of addresses.
 * Prioritizes standard headers from trusted environments like Vercel.
 */
export const getClientIpFromHeaders = (headerList: Headers) => {
  if (!config.env.trustProxyHeaders) {
    // A shared identifier deliberately fails safe. Deployments should enable
    // trusted forwarding only behind an ingress that overwrites client headers.
    return "untrusted-proxy";
  }

  // Vercel and many proxies provide a reliable IP in x-real-ip
  const realIp = headerList.get("x-real-ip");
  if (realIp) return realIp.trim().slice(0, 64);

  // Fallback to x-forwarded-for, taking the first entry (client IP)
  const forwardedFor = headerList.get("x-forwarded-for");
  if (forwardedFor) {
    const ip = forwardedFor.split(",")[0]?.trim();
    if (ip) return ip;
  }

  return "unknown";
};

export const getClientIp = async () =>
  getClientIpFromHeaders(await headers());

/**
 * Apply the shared public API rate limiter for the current request.
 */
export const enforceRateLimit = async () => {
  const ip = await getClientIp();
  const result = await ratelimit.limit(ip);

  return result.success;
};
