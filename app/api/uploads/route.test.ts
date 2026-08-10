import { NextRequest, NextResponse } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const config = vi.hoisted(() => ({
  env: {
    allowPublicSignup: false,
    appEnvironment: "development",
    imagekit: {
      privateKey: "private_test_key",
    },
  },
}));
const enforceSameOriginRequest = vi.hoisted(() => vi.fn());
const enforceRateLimit = vi.hoisted(() => vi.fn());
const requireAdmin = vi.hoisted(() => vi.fn());
const guardToResponse = vi.hoisted(() => vi.fn());
const upload = vi.hoisted(() => vi.fn());
const toFile = vi.hoisted(() => vi.fn());
const metadata = vi.hoisted(() => vi.fn());
const toBuffer = vi.hoisted(() => vi.fn());

vi.mock("@/lib/config", () => ({ default: config }));
vi.mock("@/lib/security/same-origin", () => ({
  enforceSameOriginRequest,
}));
vi.mock("@/lib/security/api-request", () => ({ enforceRateLimit }));
vi.mock("@/lib/security/auth-guards", () => ({
  guardToResponse,
  requireAdmin,
}));
vi.mock("@/lib/security/logger", () => ({
  logError: vi.fn(),
  logInfo: vi.fn(),
  logWarn: vi.fn(),
}));
vi.mock("@imagekit/nodejs", () => ({
  default: class {
    files = { upload };
  },
  toFile,
}));
vi.mock("sharp", () => ({
  default: vi.fn(() => {
    const pipeline = {
      metadata,
      resize: vi.fn(),
      rotate: vi.fn(),
      toBuffer,
      webp: vi.fn(),
    };
    pipeline.resize.mockReturnValue(pipeline);
    pipeline.rotate.mockReturnValue(pipeline);
    pipeline.webp.mockReturnValue(pipeline);
    return pipeline;
  }),
}));

import { POST } from "./route";

const makeUploadRequest = (intent: string, file: File) => {
  const form = new FormData();
  form.set("intent", intent);
  form.set("file", file);

  return new NextRequest("http://localhost/api/uploads", {
    method: "POST",
    headers: { origin: "http://localhost" },
    body: form,
  });
};

describe("POST /api/uploads", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    config.env.appEnvironment = "development";
    enforceSameOriginRequest.mockReturnValue(null);
    enforceRateLimit.mockResolvedValue(true);
    requireAdmin.mockResolvedValue({
      ok: true,
      user: { id: "admin-id" },
    });
    guardToResponse.mockReturnValue(
      NextResponse.json({ error: "Forbidden" }, { status: 403 }),
    );
    metadata.mockResolvedValue({ width: 800, height: 1200 });
    toBuffer.mockResolvedValue(Buffer.from("normalized-webp"));
    toFile.mockResolvedValue({ prepared: true });
    upload.mockResolvedValue({
      fileId: "imagekit-file-id",
      size: 15,
      url: "https://ik.imagekit.io/mundia/books/covers/safe.webp",
    });
  });

  it("rejects cross-origin requests before authorization or storage", async () => {
    enforceSameOriginRequest.mockReturnValue(
      NextResponse.json({ error: "Invalid origin" }, { status: 403 }),
    );

    const response = await POST(
      makeUploadRequest(
        "book-cover",
        new File(["image"], "cover.png", { type: "image/png" }),
      ),
    );

    expect(response.status).toBe(403);
    expect(requireAdmin).not.toHaveBeenCalled();
    expect(upload).not.toHaveBeenCalled();
  });

  it("rejects malformed upload media types without raising a server error", async () => {
    const response = await POST(
      new NextRequest("http://localhost/api/uploads", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          origin: "http://localhost",
        },
        body: "{}",
      }),
    );

    expect(response.status).toBe(415);
    await expect(response.json()).resolves.toEqual({
      error: "Upload requests must use multipart form data",
    });
    expect(requireAdmin).not.toHaveBeenCalled();
    expect(upload).not.toHaveBeenCalled();
  });

  it("rejects non-admin book media uploads before storage", async () => {
    requireAdmin.mockResolvedValue({ ok: false });

    const response = await POST(
      makeUploadRequest(
        "book-cover",
        new File(["image"], "cover.png", { type: "image/png" }),
      ),
    );

    expect(response.status).toBe(403);
    expect(upload).not.toHaveBeenCalled();
  });

  it("keeps staging video ingestion closed until malware scanning exists", async () => {
    config.env.appEnvironment = "staging";

    const response = await POST(
      makeUploadRequest(
        "book-video",
        new File(["untrusted"], "trailer.mp4", { type: "video/mp4" }),
      ),
    );

    expect(response.status).toBe(503);
    expect(upload).not.toHaveBeenCalled();
  });

  it("rejects images that cannot be safely decoded", async () => {
    metadata.mockRejectedValue(new Error("invalid image"));

    const response = await POST(
      makeUploadRequest(
        "book-cover",
        new File(["not-an-image"], "cover.png", { type: "image/png" }),
      ),
    );

    expect(response.status).toBe(415);
    expect(upload).not.toHaveBeenCalled();
  });

  it("re-encodes images and uses non-overwriting server-side uploads", async () => {
    const response = await POST(
      makeUploadRequest(
        "book-cover",
        new File(["image"], "cover.png", { type: "image/png" }),
      ),
    );

    expect(response.status).toBe(201);
    expect(await response.json()).toEqual({
      fileId: "imagekit-file-id",
      url: "https://ik.imagekit.io/mundia/books/covers/safe.webp",
    });
    expect(toFile).toHaveBeenCalledWith(
      Buffer.from("normalized-webp"),
      expect.stringMatching(/^[0-9a-f-]+\.webp$/),
      { type: "image/webp" },
    );
    expect(upload).toHaveBeenCalledWith(
      expect.objectContaining({
        folder: "books/covers",
        overwriteFile: false,
        tags: ["intent:book-cover", "server-verified"],
        useUniqueFileName: false,
      }),
    );
  });
});
