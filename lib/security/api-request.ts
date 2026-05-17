import { headers } from "next/headers";

import ratelimit from "@/lib/ratelimit";

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
 */
export const getClientIp = async () => {
  const headerList = await headers();
  const forwardedFor = headerList.get("x-forwarded-for");
  const realIp = headerList.get("x-real-ip");

  return forwardedFor?.split(",")[0]?.trim() || realIp || "127.0.0.1";
};

/**
 * Apply the shared public API rate limiter for the current request.
 */
export const enforceRateLimit = async () => {
  const ip = await getClientIp();
  const result = await ratelimit.limit(ip);

  return result.success;
};
