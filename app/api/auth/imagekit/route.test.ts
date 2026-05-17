import { describe, expect, it, vi, beforeEach } from "vitest";
import { NextRequest } from "next/server";
import { GET } from "./route";

const requireAdminMock = vi.hoisted(() => vi.fn());
const rateLimitMock = vi.hoisted(() => vi.fn());
const logErrorMock = vi.hoisted(() => vi.fn());

vi.mock("imagekit", () => ({
  default: vi.fn().mockImplementation(() => ({
    getAuthenticationParameters: vi.fn(() => ({
      token: "token",
      expire: 123,
      signature: "signature",
    })),
  })),
}));

vi.mock("next/headers", () => ({
  headers: vi.fn(async () => ({
    get: vi.fn(() => "127.0.0.1"),
  })),
}));

vi.mock("@/lib/ratelimit", () => ({
  default: {
    limit: rateLimitMock,
  },
}));

vi.mock("@/lib/config", () => ({
  default: {
    env: {
      imagekit: {
        publicKey: "public-key",
        privateKey: "private-key",
        urlEndpoint: "https://ik.example.com",
      },
    },
  },
}));

vi.mock("@/lib/security/auth-guards", () => ({
  requireAdmin: requireAdminMock,
  guardToResponse: vi.fn(() => Response.json({ error: "Forbidden" }, { status: 403 })),
}));

vi.mock("@/lib/security/logger", () => ({
  logError: logErrorMock,
  logInfo: vi.fn(),
  logWarn: vi.fn(),
}));

const request = (intent?: string, params: Record<string, string> = {}) => {
  const searchParams = new URLSearchParams(params);
  if (intent) {
    searchParams.set("intent", intent);
  }

  return new NextRequest(
    `http://localhost/api/auth/imagekit?${searchParams.toString()}`,
  );
};

describe("ImageKit auth route", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    rateLimitMock.mockResolvedValue({ success: true });
    requireAdminMock.mockResolvedValue({
      ok: true,
      user: { id: "admin-1", role: "ADMIN", status: "APPROVED" },
    });
  });

  it("rejects missing or invalid upload intents", async () => {
    const response = await GET(request("bad-intent"));

    expect(response.status).toBe(400);
  });

  it("issues limited unauthenticated signup card upload auth", async () => {
    const response = await GET(request("signup-card"));
    const body = await response.json();

    expect(response.status).toBe(200);
    expect(requireAdminMock).not.toHaveBeenCalled();
    expect(body.policy).toMatchObject({
      folder: "ids",
      maxBytes: 20 * 1024 * 1024,
      requiresAdmin: false,
    });
    expect(body.policy.allowedMimeTypes).toEqual(["image/"]);
  });

  it("requires admin access for book upload intents", async () => {
    requireAdminMock.mockResolvedValueOnce({
      ok: false,
      status: 403,
      error: "Forbidden",
      message: "Admin access required",
    });

    const response = await GET(request("book-cover"));

    expect(response.status).toBe(403);
    expect(requireAdminMock).toHaveBeenCalled();
  });

  it("rejects invalid MIME families and oversized files", async () => {
    const invalidMime = await GET(
      request("signup-card", { mimeType: "application/pdf" }),
    );
    expect(invalidMime.status).toBe(400);

    const oversized = await GET(
      request("signup-card", { mimeType: "image/png", fileSize: "20971521" }),
    );
    expect(oversized.status).toBe(400);
  });
});
