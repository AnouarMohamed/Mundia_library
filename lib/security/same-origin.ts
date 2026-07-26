import { type NextResponse } from "next/server";

import { forbiddenResponse, jsonError } from "@/lib/security/api-response";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export type SameOriginRequestOptions = {
  /**
   * Require an application/json (or application/*+json) media type.
   *
   * Enable this for JSON mutation endpoints. Keep it disabled for body-less
   * mutations; multipart uploads and signed webhooks need their own policies.
   */
  requireJson?: boolean;
};

const parseSerializedOrigin = (value: string | null): string | null => {
  if (!value || value === "null") {
    return null;
  }

  try {
    const parsed = new URL(value);

    if (
      !["http:", "https:"].includes(parsed.protocol) ||
      parsed.username ||
      parsed.password ||
      parsed.pathname !== "/" ||
      parsed.search ||
      parsed.hash
    ) {
      return null;
    }

    // A browser Origin header is a serialized origin, not an arbitrary URL.
    if (value !== parsed.origin) {
      return null;
    }

    return parsed.origin;
  } catch {
    return null;
  }
};

const isJsonMediaType = (contentType: string | null) => {
  if (!contentType) {
    return false;
  }

  const mediaType = contentType.split(";", 1)[0]?.trim().toLowerCase();

  return (
    mediaType === "application/json" ||
    Boolean(
      mediaType?.startsWith("application/") && mediaType.endsWith("+json"),
    )
  );
};

/**
 * Enforce an exact same-origin policy for browser-facing mutation endpoints.
 *
 * The request URL must preserve the public scheme and host. Production proxies
 * must therefore overwrite untrusted forwarding headers and forward the
 * external host/protocol correctly.
 *
 * This deliberately rejects missing Origin headers on unsafe methods. Do not
 * apply it to provider webhooks, native clients, or service-to-service routes;
 * those callers need a separate signed-request or bearer-token policy.
 *
 * @returns `null` when the request is allowed, otherwise a ready-to-return
 * error response.
 */
export const enforceSameOriginRequest = (
  request: Request,
  options: SameOriginRequestOptions = {},
): NextResponse | null => {
  const method = request.method.toUpperCase();

  if (SAFE_METHODS.has(method)) {
    return null;
  }

  // Fetch Metadata is defense-in-depth. Origin remains authoritative because
  // same-site sibling domains are still cross-origin.
  if (request.headers.get("sec-fetch-site") === "cross-site") {
    return forbiddenResponse("Cross-site request rejected");
  }

  const suppliedOrigin = parseSerializedOrigin(request.headers.get("origin"));
  const expectedOrigin = parseSerializedOrigin(new URL(request.url).origin);

  if (
    !suppliedOrigin ||
    !expectedOrigin ||
    suppliedOrigin !== expectedOrigin
  ) {
    return forbiddenResponse("Cross-site request rejected");
  }

  if (options.requireJson && !isJsonMediaType(request.headers.get("content-type"))) {
    return jsonError(
      "Unsupported Media Type",
      "Content-Type must be application/json",
      415,
    );
  }

  return null;
};
