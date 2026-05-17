import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { GET } from "./route";
import { db } from "@/database/drizzle";

const enforceRateLimitMock = vi.hoisted(() => vi.fn());

vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
  },
}));

vi.mock("@/lib/security/api-request", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/security/api-request")>()),
  enforceRateLimit: enforceRateLimitMock,
}));

vi.mock("@/lib/security/logger", () => ({
  logError: vi.fn(),
}));

describe("GET /api/books/[id]", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    enforceRateLimitMock.mockResolvedValue(true);
  });

  it("rejects invalid UUIDs before querying the database", async () => {
    const response = await GET(
      new NextRequest("http://localhost/api/books/not-a-uuid"),
      { params: Promise.resolve({ id: "not-a-uuid" }) }
    );

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      success: false,
      error: "Bad Request",
      message: "Invalid book ID",
    });
    expect(db.select).not.toHaveBeenCalled();
  });

  it("returns a standard rate-limit response before querying", async () => {
    enforceRateLimitMock.mockResolvedValueOnce(false);

    const response = await GET(
      new NextRequest(
        "http://localhost/api/books/550e8400-e29b-41d4-a716-446655440000"
      ),
      {
        params: Promise.resolve({
          id: "550e8400-e29b-41d4-a716-446655440000",
        }),
      }
    );

    expect(response.status).toBe(429);
    expect(await response.json()).toMatchObject({
      success: false,
      error: "Too Many Requests",
    });
    expect(db.select).not.toHaveBeenCalled();
  });
});
