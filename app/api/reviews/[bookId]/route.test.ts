import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { GET } from "./route";
import { db } from "@/database/drizzle";

const authMock = vi.hoisted(() => vi.fn());
const enforceRateLimitMock = vi.hoisted(() => vi.fn());

vi.mock("@/auth", () => ({
  auth: authMock,
}));

vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
  },
}));

vi.mock("@/lib/security/api-request", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/security/api-request")>()),
  enforceRateLimit: enforceRateLimitMock,
}));

vi.mock("@/lib/security/auth-guards", () => ({
  guardToResponse: vi.fn(),
  requireApprovedUser: vi.fn(),
}));

vi.mock("@/lib/security/logger", () => ({
  logError: vi.fn(),
}));

describe("GET /api/reviews/[bookId]", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    enforceRateLimitMock.mockResolvedValue(true);
    authMock.mockResolvedValue({ user: { id: "user-1" } });
  });

  it("rejects invalid book IDs before querying", async () => {
    const response = await GET(
      new NextRequest("http://localhost/api/reviews/not-a-uuid"),
      { params: Promise.resolve({ bookId: "not-a-uuid" }) }
    );

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      success: false,
      message: "Invalid book ID",
    });
    expect(db.select).not.toHaveBeenCalled();
  });

  it("does not expose reviewer email or user ID", async () => {
    vi.mocked(db.select).mockReturnValueOnce({
      from: vi.fn().mockReturnValue({
        innerJoin: vi.fn().mockReturnValue({
          where: vi.fn().mockReturnValue({
            orderBy: vi.fn().mockResolvedValue([
              {
                id: "review-1",
                userId: "user-1",
                rating: 5,
                comment: "Useful",
                createdAt: null,
                updatedAt: null,
                userFullName: "Test User",
              },
            ]),
          }),
        }),
      }),
    } as never);

    const response = await GET(
      new NextRequest(
        "http://localhost/api/reviews/550e8400-e29b-41d4-a716-446655440000"
      ),
      {
        params: Promise.resolve({
          bookId: "550e8400-e29b-41d4-a716-446655440000",
        }),
      }
    );

    expect(response.status).toBe(200);
    const body = await response.json();

    expect(body.reviews).toEqual([
      {
        id: "review-1",
        rating: 5,
        comment: "Useful",
        createdAt: null,
        updatedAt: null,
        userFullName: "Test User",
        isOwner: true,
      },
    ]);
    expect(JSON.stringify(body)).not.toMatch(/userEmail|userId/i);
  });
});
