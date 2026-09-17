import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  getShadowConfig,
  getShadowParityStats,
  resetShadowParityStats,
  shadowEvaluateBorrowRequest,
  shadowEvaluateApproveLoan,
  shadowEvaluateReturnLoan,
} from "./circulation-shadow";

describe("circulation-shadow service", () => {
  const originalEnv = { ...process.env };

  beforeEach(() => {
    process.env = { ...originalEnv };
    resetShadowParityStats();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  it("reads config accurately from environment", () => {
    process.env.CIRCULATION_SHADOW_ENABLED = "true";
    process.env.CIRCULATION_SERVICE_URL = "http://localhost:9090";
    process.env.CIRCULATION_PRIMARY_WRITER = "KOTLIN_SERVICE";

    const config = getShadowConfig();
    expect(config.enabled).toBe(true);
    expect(config.serviceUrl).toBe("http://localhost:9090");
    expect(config.primaryWriter).toBe("KOTLIN_SERVICE");
  });

  it("returns null when shadow evaluation is disabled", async () => {
    process.env.CIRCULATION_SHADOW_ENABLED = "false";

    const result = await shadowEvaluateBorrowRequest({
      userId: "11111111-1111-4111-8111-111111111111",
      bookId: "22222222-2222-4222-8222-222222222222",
      legacySuccess: true,
    });

    expect(result).toBeNull();
    expect(getShadowParityStats().totalEvaluations).toBe(0);
  });

  it("evaluates parity match when legacy and shadow service succeed", async () => {
    process.env.CIRCULATION_SHADOW_ENABLED = "true";
    process.env.CIRCULATION_SERVICE_URL = "http://127.0.0.1:8080";

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({ loanId: "33333333-3333-4333-8333-333333333333" }),
    });
    vi.stubGlobal("fetch", mockFetch);

    const result = await shadowEvaluateBorrowRequest({
      userId: "11111111-1111-4111-8111-111111111111",
      bookId: "22222222-2222-4222-8222-222222222222",
      legacySuccess: true,
    });

    expect(result).not.toBeNull();
    expect(result?.matched).toBe(true);
    expect(result?.legacySuccess).toBe(true);
    expect(result?.shadowSuccess).toBe(true);

    const stats = getShadowParityStats();
    expect(stats.totalEvaluations).toBe(1);
    expect(stats.matches).toBe(1);
    expect(stats.mismatches).toBe(0);
  });

  it("evaluates parity mismatch when legacy succeeds but shadow fails", async () => {
    process.env.CIRCULATION_SHADOW_ENABLED = "true";

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => "Insufficient inventory",
    });
    vi.stubGlobal("fetch", mockFetch);

    const result = await shadowEvaluateApproveLoan({
      recordId: "33333333-3333-4333-8333-333333333333",
      legacySuccess: true,
    });

    expect(result).not.toBeNull();
    expect(result?.matched).toBe(false);
    expect(result?.legacySuccess).toBe(true);
    expect(result?.shadowSuccess).toBe(false);
    expect(result?.shadowError).toBe("Insufficient inventory");

    const stats = getShadowParityStats();
    expect(stats.totalEvaluations).toBe(1);
    expect(stats.matches).toBe(0);
    expect(stats.mismatches).toBe(1);
  });

  it("handles fetch network failure gracefully without throwing", async () => {
    process.env.CIRCULATION_SHADOW_ENABLED = "true";

    const mockFetch = vi.fn().mockRejectedValue(new Error("Network connection refused"));
    vi.stubGlobal("fetch", mockFetch);

    const result = await shadowEvaluateReturnLoan({
      recordId: "33333333-3333-4333-8333-333333333333",
      legacySuccess: true,
    });

    expect(result).not.toBeNull();
    expect(result?.matched).toBe(false);
    expect(result?.shadowSuccess).toBe(false);
    expect(result?.shadowError).toBe("Network connection refused");

    const stats = getShadowParityStats();
    expect(stats.totalEvaluations).toBe(1);
    expect(stats.shadowFailures).toBe(1);
  });
});
