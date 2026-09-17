/**
 * Circulation Shadow Evaluator (Phase 3 Cutover)
 *
 * This service implements non-blocking shadow evaluation during Phase 3 of the
 * production overhaul. It mirrors circulation actions (request, approve, return, renew)
 * from the Next.js BFF to the Kotlin circulation service, comparing decision outputs
 * and tracking parity metrics without affecting primary user flows.
 */

import { logError, logInfo } from "@/lib/security/logger";

export type PrimaryWriter = "LEGACY" | "KOTLIN_SERVICE";

export interface ShadowConfig {
  enabled: boolean;
  serviceUrl: string;
  primaryWriter: PrimaryWriter;
  timeoutMs: number;
}

export interface ParityResult {
  operation: string;
  matched: boolean;
  legacySuccess: boolean;
  shadowSuccess: boolean;
  legacyError?: string;
  shadowError?: string;
  durationMs: number;
}

// Global parity statistics for runtime observation during Phase 3 soak
const shadowStats = {
  totalEvaluations: 0,
  matches: 0,
  mismatches: 0,
  shadowFailures: 0,
};

/**
 * Returns current shadow configuration from environment.
 */
export function getShadowConfig(): ShadowConfig {
  const enabled = process.env.CIRCULATION_SHADOW_ENABLED === "true";
  const serviceUrl =
    process.env.CIRCULATION_SERVICE_URL || "http://127.0.0.1:8080";
  const primaryWriter: PrimaryWriter =
    process.env.CIRCULATION_PRIMARY_WRITER === "KOTLIN_SERVICE"
      ? "KOTLIN_SERVICE"
      : "LEGACY";
  const timeoutMs = parseInt(
    process.env.CIRCULATION_SHADOW_TIMEOUT_MS || "2000",
    10
  );

  return { enabled, serviceUrl, primaryWriter, timeoutMs };
}

/**
 * Returns current runtime parity stats.
 */
export function getShadowParityStats() {
  return { ...shadowStats };
}

/**
 * Resets shadow parity statistics (useful for tests and metrics rotation).
 */
export function resetShadowParityStats() {
  shadowStats.totalEvaluations = 0;
  shadowStats.matches = 0;
  shadowStats.mismatches = 0;
  shadowStats.shadowFailures = 0;
}

/**
 * Executes a shadow API HTTP call against the Kotlin circulation-service.
 */
async function sendShadowCommand(
  path: string,
  method: "POST" = "POST",
  body?: Record<string, unknown>,
  idempotencyKey?: string,
  authHeader?: string
): Promise<{ ok: boolean; status: number; data?: unknown; error?: string }> {
  const config = getShadowConfig();
  const url = `${config.serviceUrl}${path}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.timeoutMs);

  try {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey || crypto.randomUUID(),
    };

    if (authHeader) {
      headers["Authorization"] = authHeader;
    }

    const res = await fetch(url, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });

    clearTimeout(timer);

    if (res.ok) {
      const data = await res.json().catch(() => null);
      return { ok: true, status: res.status, data };
    } else {
      const errText = await res.text().catch(() => "Unknown error");
      return { ok: false, status: res.status, error: errText };
    }
  } catch (err: unknown) {
    clearTimeout(timer);
    const errMsg = err instanceof Error ? err.message : String(err);
    return { ok: false, status: 0, error: errMsg };
  }
}

/**
 * Evaluates decision parity for a borrow request command.
 */
export async function shadowEvaluateBorrowRequest(params: {
  userId: string;
  bookId: string;
  legacySuccess: boolean;
  legacyError?: string;
  authToken?: string;
}): Promise<ParityResult | null> {
  const config = getShadowConfig();
  if (!config.enabled) return null;

  const start = Date.now();
  const shadowRes = await sendShadowCommand(
    "/api/v1/circulation/loans",
    "POST",
    { memberId: params.userId, editionId: params.bookId },
    `shadow-req-${params.userId}-${params.bookId}-${Date.now()}`,
    params.authToken ? `Bearer ${params.authToken}` : undefined
  );
  const durationMs = Date.now() - start;

  const matched = params.legacySuccess === shadowRes.ok;

  shadowStats.totalEvaluations++;
  if (matched) {
    shadowStats.matches++;
  } else {
    shadowStats.mismatches++;
    if (!shadowRes.ok && shadowRes.status === 0) {
      shadowStats.shadowFailures++;
    }
  }

  const result: ParityResult = {
    operation: "BORROW_REQUEST",
    matched,
    legacySuccess: params.legacySuccess,
    shadowSuccess: shadowRes.ok,
    legacyError: params.legacyError,
    shadowError: shadowRes.error,
    durationMs,
  };

  if (!matched) {
    logError("circulation.shadow_parity_mismatch", new Error("Parity mismatch"), {
      operation: result.operation,
      legacySuccess: result.legacySuccess,
      shadowSuccess: result.shadowSuccess,
      shadowStatus: shadowRes.status,
      legacyError: result.legacyError,
      shadowError: result.shadowError,
    });
  } else {
    logInfo("circulation.shadow_parity_match", {
      operation: result.operation,
      durationMs,
    });
  }

  return result;
}

/**
 * Evaluates decision parity for an approve loan command.
 */
export async function shadowEvaluateApproveLoan(params: {
  recordId: string;
  legacySuccess: boolean;
  legacyError?: string;
  authToken?: string;
}): Promise<ParityResult | null> {
  const config = getShadowConfig();
  if (!config.enabled) return null;

  const start = Date.now();
  const shadowRes = await sendShadowCommand(
    `/api/v1/circulation/loans/${params.recordId}/approve`,
    "POST",
    undefined,
    `shadow-app-${params.recordId}-${Date.now()}`,
    params.authToken ? `Bearer ${params.authToken}` : undefined
  );
  const durationMs = Date.now() - start;

  const matched = params.legacySuccess === shadowRes.ok;

  shadowStats.totalEvaluations++;
  if (matched) {
    shadowStats.matches++;
  } else {
    shadowStats.mismatches++;
    if (!shadowRes.ok && shadowRes.status === 0) {
      shadowStats.shadowFailures++;
    }
  }

  const result: ParityResult = {
    operation: "APPROVE_LOAN",
    matched,
    legacySuccess: params.legacySuccess,
    shadowSuccess: shadowRes.ok,
    legacyError: params.legacyError,
    shadowError: shadowRes.error,
    durationMs,
  };

  if (!matched) {
    logError("circulation.shadow_parity_mismatch", new Error("Parity mismatch"), {
      operation: result.operation,
      legacySuccess: result.legacySuccess,
      shadowSuccess: result.shadowSuccess,
      shadowStatus: shadowRes.status,
      legacyError: result.legacyError,
      shadowError: result.shadowError,
    });
  } else {
    logInfo("circulation.shadow_parity_match", {
      operation: result.operation,
      durationMs,
    });
  }

  return result;
}

/**
 * Evaluates decision parity for a return loan command.
 */
export async function shadowEvaluateReturnLoan(params: {
  recordId: string;
  legacySuccess: boolean;
  legacyError?: string;
  authToken?: string;
}): Promise<ParityResult | null> {
  const config = getShadowConfig();
  if (!config.enabled) return null;

  const start = Date.now();
  const shadowRes = await sendShadowCommand(
    `/api/v1/circulation/loans/${params.recordId}/return`,
    "POST",
    undefined,
    `shadow-ret-${params.recordId}-${Date.now()}`,
    params.authToken ? `Bearer ${params.authToken}` : undefined
  );
  const durationMs = Date.now() - start;

  const matched = params.legacySuccess === shadowRes.ok;

  shadowStats.totalEvaluations++;
  if (matched) {
    shadowStats.matches++;
  } else {
    shadowStats.mismatches++;
    if (!shadowRes.ok && shadowRes.status === 0) {
      shadowStats.shadowFailures++;
    }
  }

  const result: ParityResult = {
    operation: "RETURN_LOAN",
    matched,
    legacySuccess: params.legacySuccess,
    shadowSuccess: shadowRes.ok,
    legacyError: params.legacyError,
    shadowError: shadowRes.error,
    durationMs,
  };

  if (!matched) {
    logError("circulation.shadow_parity_mismatch", new Error("Parity mismatch"), {
      operation: result.operation,
      legacySuccess: result.legacySuccess,
      shadowSuccess: result.shadowSuccess,
      shadowStatus: shadowRes.status,
      legacyError: result.legacyError,
      shadowError: result.shadowError,
    });
  } else {
    logInfo("circulation.shadow_parity_match", {
      operation: result.operation,
      durationMs,
    });
  }

  return result;
}
