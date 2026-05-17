import { describe, it, expect, vi, beforeEach } from "vitest";
import { approveBorrowRequest } from "./borrow";
import { db } from "@/database/drizzle";

const requireAdminMock = vi.hoisted(() => vi.fn());

// Mock the dependencies
vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
    update: vi.fn(),
    from: vi.fn(),
    where: vi.fn(),
    limit: vi.fn(),
    set: vi.fn(),
    transaction: vi.fn(),
    insert: vi.fn().mockReturnValue({
      values: vi.fn().mockResolvedValue([{ insertId: 1 }]),
    }),
  },
}));

vi.mock("@/lib/security/auth-guards", () => ({
  requireAdmin: requireAdminMock,
  guardToActionError: vi.fn((guard) => ({
    success: false,
    error: guard.message,
  })),
}));

vi.mock("@/lib/admin/audit", () => ({
  logAdminAction: vi.fn(),
}));

vi.mock("@/lib/cache/revalidate", () => ({
  revalidateCatalogTags: vi.fn(),
}));

vi.mock("@/lib/services/notification-service", () => ({
  createNotification: vi.fn(),
}));

vi.mock("@/lib/security/logger", () => ({
  logError: vi.fn(),
}));

describe("approveBorrowRequest", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requireAdminMock.mockResolvedValue({
      ok: true,
      user: { id: "admin-1", role: "ADMIN", status: "APPROVED" },
    });
  });

  it("should fail if user is not an admin", async () => {
    requireAdminMock.mockResolvedValueOnce({
      ok: false,
      status: 403,
      error: "Forbidden",
      message: "Admin access required",
    });

    const result = await approveBorrowRequest("record-1");

    expect(result.success).toBe(false);
    expect(result.error).toBe("Admin access required");
  });

  it("should successfully approve a borrow request", async () => {
    const tx = {
      select: vi.fn().mockReturnValue({
        from: vi.fn().mockReturnValue({
          where: vi.fn().mockReturnValue({
            limit: vi.fn().mockResolvedValue([
              { bookId: "book-1", userId: "user-1", status: "PENDING" },
            ]),
          }),
        }),
      }),
      update: vi
        .fn()
        .mockReturnValueOnce({
          set: vi.fn().mockReturnValue({
            where: vi.fn().mockReturnValue({
              returning: vi.fn().mockResolvedValue([
                {
                  id: "book-1",
                  title: "The Clean Coder",
                  availableCopies: 9,
                },
              ]),
            }),
          }),
        })
        .mockReturnValueOnce({
          set: vi.fn().mockReturnValue({
            where: vi.fn().mockResolvedValue({}),
          }),
        }),
    };

    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveBorrowRequest("record-1");

    expect(result.success).toBe(true);
    expect(db.transaction).toHaveBeenCalled();
    expect(tx.update).toHaveBeenCalledTimes(2);
  });

  it("should reject approval when no copy can be atomically reserved", async () => {
    const tx = {
      select: vi.fn().mockReturnValue({
        from: vi.fn().mockReturnValue({
          where: vi.fn().mockReturnValue({
            limit: vi.fn().mockResolvedValue([
              { bookId: "book-1", userId: "user-1", status: "PENDING" },
            ]),
          }),
        }),
      }),
      update: vi.fn().mockReturnValue({
        set: vi.fn().mockReturnValue({
          where: vi.fn().mockReturnValue({
            returning: vi.fn().mockResolvedValue([]),
          }),
        }),
      }),
    };

    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveBorrowRequest("record-1");

    expect(result.success).toBe(false);
    expect(result.error).toBe("Book is no longer available");
  });
});
