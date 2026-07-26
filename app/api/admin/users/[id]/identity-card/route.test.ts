import { NextRequest, NextResponse } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const requireAdminRouteAccess = vi.hoisted(() => vi.fn());
const rateLimit = vi.hoisted(() => vi.fn());
const logAdminAction = vi.hoisted(() => vi.fn());
const select = vi.hoisted(() => vi.fn());
const normalizeImage = vi.hoisted(() =>
  vi.fn().mockResolvedValue(Buffer.from("normalized-image")),
);

vi.mock("@/lib/admin/route-guard", () => ({
  requireAdminRouteAccess,
}));

vi.mock("@/lib/ratelimit", () => ({
  default: { limit: rateLimit },
}));

vi.mock("@/lib/admin/audit", () => ({
  logAdminAction,
}));

vi.mock("@/database/drizzle", () => ({
  db: { select },
}));

vi.mock("@/lib/config", () => ({
  default: {
    env: {
      imagekit: {
        urlEndpoint: "https://ik.imagekit.io/mundia",
      },
    },
  },
}));

vi.mock("@/lib/security/api-request", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/security/api-request")>()),
  getClientIp: vi.fn().mockResolvedValue("test-source"),
}));

vi.mock("@/lib/security/logger", () => ({
  logError: vi.fn(),
  logWarn: vi.fn(),
}));

vi.mock("sharp", () => ({
  default: vi.fn(() => {
    const pipeline = {
      rotate: vi.fn(),
      resize: vi.fn(),
      webp: vi.fn(),
      toBuffer: normalizeImage,
    };
    pipeline.rotate.mockReturnValue(pipeline);
    pipeline.resize.mockReturnValue(pipeline);
    pipeline.webp.mockReturnValue(pipeline);
    return pipeline;
  }),
}));

import { GET } from "./route";

const userId = "550e8400-e29b-41d4-a716-446655440000";
const request = new NextRequest(
  `http://localhost/api/admin/users/${userId}/identity-card`,
);

const mockEvidenceRecord = (universityCard: string) => {
  select.mockReturnValueOnce({
    from: vi.fn().mockReturnValue({
      where: vi.fn().mockReturnValue({
        limit: vi.fn().mockResolvedValue([{ universityCard }]),
      }),
    }),
  });
};

describe("GET admin identity evidence", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    requireAdminRouteAccess.mockResolvedValue({
      ok: true,
      user: { id: "admin-id" },
    });
    rateLimit.mockResolvedValue({ success: true });
  });

  it("rejects non-admin callers before reading evidence", async () => {
    requireAdminRouteAccess.mockResolvedValue({
      ok: false,
      response: NextResponse.json({ error: "Forbidden" }, { status: 403 }),
    });

    const response = await GET(request, {
      params: Promise.resolve({ id: userId }),
    });

    expect(response.status).toBe(403);
    expect(select).not.toHaveBeenCalled();
  });

  it("never follows an evidence URL outside managed storage", async () => {
    mockEvidenceRecord("https://attacker.example/ids/card.png");

    const response = await GET(request, {
      params: Promise.resolve({ id: userId }),
    });

    expect(response.status).toBe(404);
    expect(logAdminAction).not.toHaveBeenCalled();
  });

  it("normalizes legacy evidence and requires a durable access audit", async () => {
    mockEvidenceRecord("data:image/png;base64,aGVsbG8=");

    const response = await GET(request, {
      params: Promise.resolve({ id: userId }),
    });

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("image/webp");
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(Buffer.from(await response.arrayBuffer()).toString()).toBe(
      "normalized-image",
    );
    expect(logAdminAction).toHaveBeenCalledWith(
      "admin-id",
      "VIEW_IDENTITY_EVIDENCE",
      userId,
      "USER",
      { source: "legacy-inline" },
      { mandatory: true },
    );
  });

  it("stops reading oversized evidence when storage omits content-length", async () => {
    mockEvidenceRecord("ids/oversized.png");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(new Uint8Array(10 * 1024 * 1024 + 1), {
          status: 200,
          headers: { "content-type": "image/png" },
        }),
      ),
    );

    const response = await GET(request, {
      params: Promise.resolve({ id: userId }),
    });

    expect(response.status).toBe(502);
    expect(normalizeImage).not.toHaveBeenCalled();
    expect(logAdminAction).not.toHaveBeenCalled();
  });
});
