import { beforeEach, describe, expect, it, vi } from "vitest";

import { GET } from "./route";

const { requireApprovedUserMock, getVerifiedLearningResourceMock } = vi.hoisted(
  () => ({
    requireApprovedUserMock: vi.fn(),
    getVerifiedLearningResourceMock: vi.fn(),
  }),
);

vi.mock("@/lib/security/auth-guards", () => ({
  requireApprovedUser: requireApprovedUserMock,
}));

vi.mock("@/lib/learning-resources/queries", () => ({
  getVerifiedLearningResource: getVerifiedLearningResourceMock,
}));

const RESOURCE_ID = "550e8400-e29b-41d4-a716-446655440000";

describe("GET /api/learning-resources/[id]/download", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requireApprovedUserMock.mockResolvedValue({
      ok: true,
      user: { id: "user-1" },
    });
  });

  it("requires an approved user", async () => {
    requireApprovedUserMock.mockResolvedValueOnce({ ok: false });

    const response = await GET(new Request("https://library.test"), {
      params: Promise.resolve({ id: RESOURCE_ID }),
    });

    expect(response.status).toBe(401);
    expect(getVerifiedLearningResourceMock).not.toHaveBeenCalled();
  });

  it("rejects malformed resource identifiers before querying", async () => {
    const response = await GET(new Request("https://library.test"), {
      params: Promise.resolve({ id: "not-a-uuid" }),
    });

    expect(response.status).toBe(400);
    expect(getVerifiedLearningResourceMock).not.toHaveBeenCalled();
  });

  it("redirects verified downloads to the official HTTPS source", async () => {
    getVerifiedLearningResourceMock.mockResolvedValueOnce({
      downloadUrl: "https://official.example.edu/book.pdf",
    });

    const response = await GET(new Request("https://library.test"), {
      params: Promise.resolve({ id: RESOURCE_ID }),
    });

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(
      "https://official.example.edu/book.pdf",
    );
    expect(response.headers.get("cache-control")).toBe("private, no-store");
    expect(response.headers.get("referrer-policy")).toBe("no-referrer");
  });

  it.each([
    ["missing resource", null],
    ["missing download", { downloadUrl: null }],
    ["malformed destination", { downloadUrl: "https://" }],
    ["non-HTTPS destination", { downloadUrl: "http://example.edu/book.pdf" }],
    [
      "credential-bearing destination",
      { downloadUrl: "https://user:password@example.edu/book.pdf" },
    ],
  ])("returns 404 for %s", async (_label, resource) => {
    getVerifiedLearningResourceMock.mockResolvedValueOnce(resource);

    const response = await GET(new Request("https://library.test"), {
      params: Promise.resolve({ id: RESOURCE_ID }),
    });

    expect(response.status).toBe(404);
  });
});
