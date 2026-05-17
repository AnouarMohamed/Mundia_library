/**
 * Renewal Actions Unit Tests
 * 
 * This suite provides comprehensive testing for the renewal request server actions.
 * It covers authentication checks, ownership validation, eligibility criteria,
 * and successful record creation, ensuring the business logic remains robust.
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { requestRenewal } from "./renewal";
import { db } from "@/database/drizzle";

const requireApprovedUserMock = vi.hoisted(() => vi.fn());

// Mock dependencies
vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
    insert: vi.fn(),
    from: vi.fn(),
    where: vi.fn(),
    limit: vi.fn(),
  },
}));

vi.mock("@/lib/security/auth-guards", () => ({
  requireApprovedUser: requireApprovedUserMock,
}));

vi.mock("@/lib/cache/revalidate", () => ({
  revalidateCatalogTags: vi.fn(),
}));

describe("requestRenewal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requireApprovedUserMock.mockResolvedValue({
      ok: true,
      user: { id: "user-1", role: "USER", status: "APPROVED" },
    });
  });

  it("should fail if user is not authenticated or approved", async () => {
    requireApprovedUserMock.mockResolvedValueOnce({
      ok: false,
      status: 401,
      error: "Unauthorized",
      message: "Authentication required",
    });

    const result = await requestRenewal({ borrowRecordId: "record-1" });

    expect(result.success).toBe(false);
    expect(result.error).toBe("Authentication required");
  });

  it("should fail if borrow record does not exist", async () => {
    (db.select as any).mockReturnValue({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([]),
        }),
      }),
    });

    const result = await requestRenewal({ borrowRecordId: "record-1" });

    expect(result.success).toBe(false);
    expect(result.error).toBe("Borrow record not found.");
  });

  it("should fail if borrow record belongs to another user", async () => {
    (db.select as any).mockReturnValue({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ userId: "user-2", status: "BORROWED" }]),
        }),
      }),
    });

    const result = await requestRenewal({ borrowRecordId: "record-1" });

    expect(result.success).toBe(false);
    expect(result.error).toContain("Unauthorized");
  });

  it("should fail if book is not currently borrowed", async () => {
    (db.select as any).mockReturnValue({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ userId: "user-1", status: "RETURNED" }]),
        }),
      }),
    });

    const result = await requestRenewal({ borrowRecordId: "record-1" });

    expect(result.success).toBe(false);
    expect(result.error).toContain("active borrowings");
  });

  it("should successfully create a renewal request", async () => {
    // Mock borrow record check
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ userId: "user-1", status: "BORROWED" }]),
        }),
      }),
    });

    // Mock existing request check (empty)
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([]),
        }),
      }),
    });

    // Mock insert
    (db.insert as any).mockReturnValue({
      values: vi.fn().mockResolvedValue({}),
    });

    const result = await requestRenewal({ borrowRecordId: "record-1", reason: "Need more time" });

    expect(result.success).toBe(true);
    expect(db.insert).toHaveBeenCalled();
  });
});
