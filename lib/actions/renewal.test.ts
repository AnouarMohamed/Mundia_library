import { describe, it, expect, vi, beforeEach } from "vitest";
import { requestRenewal } from "./renewal";
import { db } from "@/database/drizzle";
import { auth } from "@/auth";

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

vi.mock("@/auth", () => ({
  auth: vi.fn(),
}));

vi.mock("@/lib/cache/revalidate", () => ({
  revalidateCatalogTags: vi.fn(),
}));

describe("requestRenewal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should fail if user is not authenticated", async () => {
    (auth as any).mockResolvedValue(null);

    const result = await requestRenewal({ borrowRecordId: "record-1" });

    expect(result.success).toBe(false);
    expect(result.error).toBe("You must be logged in to request a renewal.");
  });

  it("should fail if borrow record does not exist", async () => {
    (auth as any).mockResolvedValue({ user: { id: "user-1" } });
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
    (auth as any).mockResolvedValue({ user: { id: "user-1" } });
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
    (auth as any).mockResolvedValue({ user: { id: "user-1" } });
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
    (auth as any).mockResolvedValue({ user: { id: "user-1" } });
    
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
