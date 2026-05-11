import { describe, it, expect, vi, beforeEach } from "vitest";
import { approveBorrowRequest } from "./borrow";
import { db } from "@/database/drizzle";
import { auth } from "@/auth";

// Mock the dependencies
vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
    update: vi.fn(),
    from: vi.fn(),
    where: vi.fn(),
    limit: vi.fn(),
    set: vi.fn(),
    insert: vi.fn().mockReturnValue({
      values: vi.fn().mockResolvedValue([{ insertId: 1 }]),
    }),
  },
}));

vi.mock("@/auth", () => ({
  auth: vi.fn(),
}));

vi.mock("@/lib/admin/audit", () => ({
  logAdminAction: vi.fn(),
}));

vi.mock("@/lib/cache/revalidate", () => ({
  revalidateCatalogTags: vi.fn(),
}));

describe("approveBorrowRequest", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should fail if user is not an admin", async () => {
    (auth as any).mockResolvedValue({ user: { role: "USER" } });

    const result = await approveBorrowRequest("record-1");

    expect(result.success).toBe(false);
    expect(result.error).toBe("Unauthorized");
  });

  it("should successfully approve a borrow request", async () => {
    (auth as any).mockResolvedValue({ user: { id: "admin-1", role: "ADMIN" } });

    // Mock record retrieval
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ bookId: "book-1", userId: "user-1" }]),
        }),
      }),
    });

    // Mock book availability check
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ availableCopies: 10 }]),
        }),
      }),
    });

    // Mock updates
    (db.update as any).mockReturnValue({
      set: vi.fn().mockReturnValue({
        where: vi.fn().mockResolvedValue({}),
      }),
    });

    const result = await approveBorrowRequest("record-1");

    expect(result.success).toBe(true);
    expect(db.update).toHaveBeenCalledTimes(2); // One for record, one for book
  });
});
