import { describe, it, expect, vi, beforeEach } from "vitest";
import { borrowBook } from "./book";
import { db } from "@/database/drizzle";

// Mock the database
vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
    insert: vi.fn(),
    from: vi.fn(),
    where: vi.fn(),
    limit: vi.fn(),
  },
}));

// Mock the revalidate function
vi.mock("@/lib/cache/revalidate", () => ({
  revalidateCatalogTags: vi.fn(),
}));

describe("borrowBook", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should fail if book is not available", async () => {
    // Mock db.select().from().where().limit() to return an empty array or 0 copies
    (db.select as any).mockReturnValue({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ availableCopies: 0 }]),
        }),
      }),
    });

    const result = await borrowBook({ userId: "user-1", bookId: "book-1" });

    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error).toBe("Book is not available for borrowing");
    }
  });

  it("should successfully create a pending borrow request", async () => {
    // Mock book check
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ availableCopies: 5 }]),
        }),
      }),
    });

    // Mock insert
    (db.insert as any).mockReturnValue({
      values: vi.fn().mockResolvedValue({}),
    });

    // Mock record retrieval after insert
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([
            {
              id: "record-1",
              userId: "user-1",
              bookId: "book-1",
              status: "PENDING",
            },
          ]),
        }),
      }),
    });

    const result = await borrowBook({ userId: "user-1", bookId: "book-1" });

    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data[0].status).toBe("PENDING");
    }
  });
});
