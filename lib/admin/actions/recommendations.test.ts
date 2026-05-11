import { describe, it, expect, vi, beforeEach } from "vitest";
import { generateCollaborativeRecommendations } from "./recommendations";
import { db } from "@/database/drizzle";

// Mock dependencies
vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
    from: vi.fn(),
    where: vi.fn(),
    limit: vi.fn(),
    innerJoin: vi.fn(),
    groupBy: vi.fn(),
    orderBy: vi.fn(),
  },
}));

describe("generateCollaborativeRecommendations", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should return empty array if user has no borrow history", async () => {
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockResolvedValue([]),
      }),
    });

    const result = await generateCollaborativeRecommendations("user-1");

    expect(result).toEqual([]);
  });

  it("should successfully generate recommendations based on similar users", async () => {
    // 1. Mock user history
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockResolvedValue([{ bookId: "book-1" }]),
      }),
    });

    // 2. Mock similar users find
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockReturnValue({
          limit: vi.fn().mockResolvedValue([{ userId: "user-2" }, { userId: "user-3" }]),
        }),
      }),
    });

    // 3. Mock recommended books find
    (db.select as any).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        innerJoin: vi.fn().mockReturnValue({
          where: vi.fn().mockReturnValue({
            groupBy: vi.fn().mockReturnValue({
              orderBy: vi.fn().mockReturnValue({
                limit: vi.fn().mockResolvedValue([
                  {
                    bookId: "book-2",
                    bookTitle: "Book 2",
                    bookAuthor: "Author 2",
                    bookGenre: "Fiction",
                    rating: 5,
                    matchCount: 2,
                  },
                ]),
              }),
            }),
          }),
        }),
      }),
    });

    const result = await generateCollaborativeRecommendations("user-1");

    expect(result.length).toBe(1);
    expect(result[0].bookTitle).toBe("Book 2");
    expect(result[0].algorithm).toBe("collaborative");
    expect(result[0].score).toBe(9); // (2 * 2) + 5
  });
});
