import ImageKit from "imagekit";
import config from "@/lib/config";
import { NextRequest, NextResponse } from "next/server";
import { headers } from "next/headers";
import ratelimit from "@/lib/ratelimit";
import {
  badRequestResponse,
  internalServerErrorResponse,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { guardToResponse, requireAdmin } from "@/lib/security/auth-guards";
import { logError, logInfo, logWarn } from "@/lib/security/logger";
import { randomUUID } from "crypto";

let imagekit: ImageKit | null = null;

const uploadPolicies = {
  "signup-card": {
    folder: "ids",
    maxBytes: 20 * 1024 * 1024,
    allowedMimeTypes: ["image/"],
    requiresAdmin: false,
  },
  "book-cover": {
    folder: "books/covers",
    maxBytes: 20 * 1024 * 1024,
    allowedMimeTypes: ["image/"],
    requiresAdmin: true,
  },
  "book-video": {
    folder: "books/videos",
    maxBytes: 50 * 1024 * 1024,
    allowedMimeTypes: ["video/"],
    requiresAdmin: true,
  },
} as const;

type UploadIntent = keyof typeof uploadPolicies;

/**
 * Lazily initialize the ImageKit client from env config.
 */
const getImageKit = () => {
  if (imagekit) {
    return imagekit;
  }

  const {
    imagekit: { publicKey, privateKey, urlEndpoint },
  } = config.env;

  if (!publicKey || !privateKey || !urlEndpoint) {
    throw new Error(
      "ImageKit is not configured. Please check NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY, IMAGEKIT_PRIVATE_KEY, and NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT."
    );
  }

  imagekit = new ImageKit({ publicKey, privateKey, urlEndpoint });

  return imagekit;
};

/**
 * GET /api/auth/imagekit
 * Returns ImageKit auth parameters for client uploads.
 */
export async function GET(request: NextRequest) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    // This endpoint is used for file uploads (book covers, university cards, videos)
    // Authentication is optional to allow sign-up flow (university card upload) to work
    // Rate limiting provides protection against abuse
    const ip = (await headers()).get("x-forwarded-for") || "127.0.0.1";
    const { success } = await ratelimit.limit(ip);

    if (!success) {
      return tooManyRequestsResponse();
    }

    const intent = request.nextUrl.searchParams.get("intent") as
      | UploadIntent
      | null;

    if (!intent || !(intent in uploadPolicies)) {
      logWarn("upload.invalid_intent", { intent });
      return badRequestResponse("Invalid upload intent");
    }

    const policy = uploadPolicies[intent];
    const mimeType = request.nextUrl.searchParams.get("mimeType");
    const fileSizeParam = request.nextUrl.searchParams.get("fileSize");
    const fileSize = fileSizeParam ? Number(fileSizeParam) : undefined;

    if (
      mimeType &&
      !policy.allowedMimeTypes.some((prefix) => mimeType.startsWith(prefix))
    ) {
      logWarn("upload.invalid_mime_type", { intent, mimeType });
      return badRequestResponse("Invalid file type for upload intent");
    }

    if (fileSize !== undefined) {
      if (!Number.isFinite(fileSize) || fileSize < 0) {
        return badRequestResponse("Invalid file size");
      }

      if (fileSize > policy.maxBytes) {
        logWarn("upload.file_too_large", {
          intent,
          fileSize,
          maxBytes: policy.maxBytes,
        });
        return badRequestResponse("File is too large for upload intent");
      }
    }

    if (policy.requiresAdmin) {
      const guard = await requireAdmin();
      if (!guard.ok) {
        return guardToResponse(guard);
      }
    }

    logInfo("upload.auth_issued", {
      intent,
      folder: policy.folder,
      adminRequired: policy.requiresAdmin,
    });

    const expire = Math.floor(Date.now() / 1000) + 5 * 60;
    return NextResponse.json({
      ...getImageKit().getAuthenticationParameters(randomUUID(), expire),
      policy,
    });
  } catch (error) {
    logError("upload.auth_failed", error);
    return internalServerErrorResponse();
  }
}
