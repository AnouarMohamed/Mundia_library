import { beforeEach, describe, expect, it, vi } from "vitest";
import { db } from "@/database/drizzle";
import {
  approveAdminRequest,
  rejectAdminRequest,
} from "./admin-requests";

const mocks = vi.hoisted(() => ({
  requireAdminCapability: vi.fn(),
  logAdminAction: vi.fn(),
  logError: vi.fn(),
}));

vi.mock("@/database/drizzle", () => ({
  db: {
    transaction: vi.fn(),
  },
}));

vi.mock("@/lib/security/admin-capabilities", () => ({
  requireAdminCapability: mocks.requireAdminCapability,
}));

vi.mock("@/lib/security/auth-guards", () => ({
  requireAdmin: vi.fn(),
  requireSelfOrAdmin: vi.fn(),
  guardToActionError: vi.fn((guard) => ({
    success: false,
    error: guard.message,
  })),
}));

vi.mock("@/lib/admin/audit", () => ({
  logAdminAction: mocks.logAdminAction,
}));

vi.mock("@/lib/security/logger", () => ({
  logError: mocks.logError,
}));

const selectQuery = (rows: unknown[]) => {
  const query = {
    innerJoin: vi.fn(),
    where: vi.fn().mockReturnValue({
      limit: vi.fn().mockResolvedValue(rows),
    }),
  };
  query.innerJoin.mockReturnValue(query);

  return {
    from: vi.fn().mockReturnValue(query),
  };
};

const updateQuery = (rows: unknown[]) => ({
  set: vi.fn().mockReturnValue({
    where: vi.fn().mockReturnValue({
      returning: vi.fn().mockResolvedValue(rows),
    }),
  }),
});

const requestId = "550e8400-e29b-41d4-a716-446655440001";
const userId = "550e8400-e29b-41d4-a716-446655440002";
const adminId = "550e8400-e29b-41d4-a716-446655440003";

const approvedRequest = {
  id: requestId,
  userId,
  requestReason: "Operational support",
  status: "APPROVED",
  reviewedBy: adminId,
  reviewedAt: new Date("2026-07-26T12:00:00.000Z"),
  rejectionReason: null,
  createdAt: new Date("2026-07-25T12:00:00.000Z"),
  updatedAt: new Date("2026-07-26T12:00:00.000Z"),
};

describe("admin access request decisions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.requireAdminCapability.mockResolvedValue({
      ok: true,
      user: { id: adminId, role: "ADMIN", status: "APPROVED" },
    });
  });

  it("atomically approves the request and promotes its user", async () => {
    const tx = {
      select: vi.fn().mockReturnValue(
        selectQuery([
          {
            userId,
            status: "PENDING",
            userEmail: "user@example.com",
            userFullName: "Example User",
            userStatus: "APPROVED",
          },
        ]),
      ),
      update: vi
        .fn()
        .mockReturnValueOnce(updateQuery([approvedRequest]))
        .mockReturnValueOnce(updateQuery([{ id: userId }])),
      insert: vi.fn().mockReturnValue({
        values: vi.fn().mockResolvedValue({}),
      }),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveAdminRequest(requestId, "untrusted-id");

    expect(result.success).toBe(true);
    expect(result.data).toMatchObject({
      id: requestId,
      status: "APPROVED",
      userEmail: "user@example.com",
      userFullName: "Example User",
    });
    expect(tx.update).toHaveBeenCalledTimes(2);
    expect(tx.insert).toHaveBeenCalledTimes(1);
  });

  it("does not promote the user when another decision wins the request transition", async () => {
    const tx = {
      select: vi.fn().mockReturnValue(
        selectQuery([
          {
            userId,
            status: "PENDING",
            userEmail: "user@example.com",
            userFullName: "Example User",
            userStatus: "APPROVED",
          },
        ]),
      ),
      update: vi.fn().mockReturnValueOnce(updateQuery([])),
      insert: vi.fn().mockReturnValue({
        values: vi.fn().mockResolvedValue({}),
      }),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveAdminRequest(requestId, "untrusted-id");

    expect(result).toEqual({
      success: false,
      error: "This request has already been processed",
    });
    expect(tx.update).toHaveBeenCalledTimes(1);
    expect(mocks.logAdminAction).not.toHaveBeenCalled();
  });

  it("fails the transaction when the target user cannot be promoted", async () => {
    const tx = {
      select: vi.fn().mockReturnValue(
        selectQuery([
          {
            userId,
            status: "PENDING",
            userEmail: "user@example.com",
            userFullName: "Example User",
            userStatus: "APPROVED",
          },
        ]),
      ),
      update: vi
        .fn()
        .mockReturnValueOnce(updateQuery([approvedRequest]))
        .mockReturnValueOnce(updateQuery([])),
      insert: vi.fn().mockReturnValue({
        values: vi.fn().mockResolvedValue({}),
      }),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveAdminRequest(requestId, "untrusted-id");

    expect(result).toEqual({
      success: false,
      error: "User not found",
    });
    expect(mocks.logAdminAction).not.toHaveBeenCalled();
  });

  it("conditionally rejects a pending request", async () => {
    const rejectedRequest = {
      ...approvedRequest,
      status: "REJECTED",
      rejectionReason: "Insufficient justification",
    };
    const tx = {
      select: vi.fn().mockReturnValue(
        selectQuery([
          {
            status: "PENDING",
            userEmail: "user@example.com",
            userFullName: "Example User",
          },
        ]),
      ),
      update: vi.fn().mockReturnValue(updateQuery([rejectedRequest])),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await rejectAdminRequest(
      requestId,
      "untrusted-id",
      "Insufficient justification",
    );

    expect(result.success).toBe(true);
    expect(result.data).toMatchObject({
      status: "REJECTED",
      userEmail: "user@example.com",
      rejectionReason: "Insufficient justification",
    });
    expect(mocks.logAdminAction).toHaveBeenCalledTimes(1);
  });

  it("does not audit a rejection after a lost race", async () => {
    const tx = {
      select: vi.fn().mockReturnValue(
        selectQuery([
          {
            status: "PENDING",
            userEmail: "user@example.com",
            userFullName: "Example User",
          },
        ]),
      ),
      update: vi.fn().mockReturnValue(updateQuery([])),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await rejectAdminRequest(requestId, "untrusted-id");

    expect(result).toEqual({
      success: false,
      error: "This request has already been processed",
    });
    expect(mocks.logAdminAction).not.toHaveBeenCalled();
  });
});
