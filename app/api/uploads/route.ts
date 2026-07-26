import { randomUUID } from "crypto";
import ImageKit, { toFile } from "@imagekit/nodejs";
import { NextRequest, NextResponse } from "next/server";
import sharp from "sharp";

import config from "@/lib/config";
import { guardToResponse, requireAdmin } from "@/lib/security/auth-guards";
import {
  internalServerErrorResponse,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { enforceRateLimit } from "@/lib/security/api-request";
import { logError, logInfo, logWarn } from "@/lib/security/logger";
import { enforceSameOriginRequest } from "@/lib/security/same-origin";

export const runtime = "nodejs";

const uploadPolicies = {
  "signup-card": {
    folder: "ids-quarantine",
    maxBytes: 10 * 1024 * 1024,
    media: "image",
    requiresAdmin: false,
  },
  "book-cover": {
    folder: "books/covers",
    maxBytes: 10 * 1024 * 1024,
    media: "image",
    requiresAdmin: true,
  },
  "book-video": {
    folder: "books/videos",
    maxBytes: 50 * 1024 * 1024,
    media: "video",
    requiresAdmin: true,
  },
} as const;

type UploadIntent = keyof typeof uploadPolicies;

let imagekit: ImageKit | null = null;

const getImageKit = () => {
  if (imagekit) return imagekit;

  const { privateKey } = config.env.imagekit;

  if (!privateKey) {
    throw new Error("Image upload storage is not configured");
  }

  imagekit = new ImageKit({
    privateKey,
    maxRetries: 2,
    timeout: 20_000,
    logLevel: "error",
  });
  return imagekit;
};

const isUploadIntent = (value: string): value is UploadIntent =>
  value in uploadPolicies;

const detectVideoExtension = (bytes: Buffer) => {
  if (bytes.length >= 12 && bytes.subarray(4, 8).toString("ascii") === "ftyp") {
    return "mp4";
  }

  if (
    bytes.length >= 4 &&
    bytes[0] === 0x1a &&
    bytes[1] === 0x45 &&
    bytes[2] === 0xdf &&
    bytes[3] === 0xa3
  ) {
    return "webm";
  }

  return null;
};

const normalizeImage = async (bytes: Buffer) => {
  const pipeline = sharp(bytes, {
    failOn: "warning",
    limitInputPixels: 40_000_000,
  });
  const metadata = await pipeline.metadata();

  if (
    !metadata.width ||
    !metadata.height ||
    metadata.width > 12_000 ||
    metadata.height > 12_000
  ) {
    throw new Error("Invalid image dimensions");
  }

  // Decoding and re-encoding strips executable payloads and untrusted metadata.
  return pipeline
    .rotate()
    .resize({
      width: 3_000,
      height: 3_000,
      fit: "inside",
      withoutEnlargement: true,
    })
    .webp({ quality: 88 })
    .toBuffer();
};

export async function POST(request: NextRequest) {
  try {
    const sameOriginResponse = enforceSameOriginRequest(request);
    if (sameOriginResponse) return sameOriginResponse;

    if (!(await enforceRateLimit())) {
      return tooManyRequestsResponse();
    }

    const declaredLength = Number(request.headers.get("content-length") ?? "0");
    if (
      !Number.isFinite(declaredLength) ||
      declaredLength < 0 ||
      declaredLength > 52 * 1024 * 1024
    ) {
      return NextResponse.json(
        { error: "Upload request is too large" },
        { status: 413 },
      );
    }

    const formData = await request.formData();
    const intentValue = formData.get("intent");
    const fileValue = formData.get("file");

    if (
      typeof intentValue !== "string" ||
      !isUploadIntent(intentValue) ||
      !(fileValue instanceof File)
    ) {
      return NextResponse.json(
        { error: "A valid upload intent and file are required" },
        { status: 400 },
      );
    }

    const policy = uploadPolicies[intentValue];

    if (policy.requiresAdmin) {
      const guard = await requireAdmin();
      if (!guard.ok) return guardToResponse(guard);
    } else if (
      process.env.NODE_ENV === "production" ||
      !config.env.allowPublicSignup
    ) {
      // The legacy unauthenticated signup upload exists only for explicit local
      // development. Production identity moves to OIDC/invitation flows.
      return NextResponse.json(
        { error: "Public identity uploads are disabled" },
        { status: 403 },
      );
    }

    if (
      policy.media === "video" &&
      (config.env.appEnvironment === "staging" ||
        config.env.appEnvironment === "production")
    ) {
      // A container signature is not malware analysis. Keep production video
      // ingestion closed until the private quarantine/scanner pipeline exists.
      logWarn("upload.video_scanner_required", { intent: intentValue });
      return NextResponse.json(
        { error: "Video uploads are unavailable pending security scanning" },
        { status: 503 },
      );
    }

    if (fileValue.size <= 0 || fileValue.size > policy.maxBytes) {
      return NextResponse.json(
        { error: "File size is outside the allowed range" },
        { status: 413 },
      );
    }

    let bytes = Buffer.from(await fileValue.arrayBuffer());
    let extension: "webp" | "mp4" | "webm";

    if (policy.media === "image") {
      try {
        bytes = await normalizeImage(bytes);
        extension = "webp";
      } catch {
        logWarn("upload.image_decode_rejected", { intent: intentValue });
        return NextResponse.json(
          { error: "The uploaded file is not a safe, supported image" },
          { status: 415 },
        );
      }
    } else {
      const detectedExtension = detectVideoExtension(bytes);
      if (!detectedExtension) {
        logWarn("upload.video_signature_rejected", { intent: intentValue });
        return NextResponse.json(
          { error: "Only valid MP4 or WebM video files are supported" },
          { status: 415 },
        );
      }
      extension = detectedExtension;
    }

    const fileName = `${randomUUID()}.${extension}`;
    const contentType =
      extension === "webp"
        ? "image/webp"
        : extension === "mp4"
          ? "video/mp4"
          : "video/webm";
    const result = await getImageKit().files.upload({
      file: await toFile(bytes, fileName, { type: contentType }),
      fileName,
      folder: policy.folder,
      useUniqueFileName: false,
      overwriteFile: false,
      tags: [`intent:${intentValue}`, "server-verified"],
    });

    logInfo("upload.server_verified", {
      intent: intentValue,
      fileId: result.fileId,
      bytes: result.size,
    });

    return NextResponse.json(
      {
        url: result.url,
        fileId: result.fileId,
      },
      { status: 201 },
    );
  } catch (error) {
    logError("upload.server_failed", error);
    return internalServerErrorResponse();
  }
}
