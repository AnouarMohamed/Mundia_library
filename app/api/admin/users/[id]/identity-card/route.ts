import { NextRequest, NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import sharp from "sharp";

import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import config from "@/lib/config";
import { logAdminAction } from "@/lib/admin/audit";
import { requireAdminCapabilityRouteAccess } from "@/lib/admin/route-guard";
import ratelimit from "@/lib/ratelimit";
import { getClientIp, isUuid } from "@/lib/security/api-request";
import { logError, logWarn } from "@/lib/security/logger";

export const runtime = "nodejs";

const MAX_EVIDENCE_BYTES = 10 * 1024 * 1024;
const ALLOWED_IMAGE_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
]);

const evidenceResponse = (bytes: Uint8Array, contentType: string) => {
  const body = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(body).set(bytes);

  return new NextResponse(body, {
    status: 200,
    headers: {
      "Cache-Control": "private, no-store, max-age=0",
      "Content-Type": contentType,
      "Content-Disposition": 'inline; filename="identity-evidence"',
      "Cross-Origin-Resource-Policy": "same-origin",
      "X-Content-Type-Options": "nosniff",
    },
  });
};

const readBoundedResponseBody = async (
  response: Response,
  maxBytes: number,
) => {
  if (!response.body) {
    throw new Error("Identity evidence response has no body");
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;

    totalBytes += value.byteLength;
    if (totalBytes > maxBytes) {
      await reader.cancel("Identity evidence exceeded the byte limit");
      throw new Error("Identity evidence exceeded the byte limit");
    }
    chunks.push(value);
  }

  if (totalBytes === 0) {
    throw new Error("Identity evidence response is empty");
  }

  return Buffer.concat(chunks.map((chunk) => Buffer.from(chunk)), totalBytes);
};

const normalizeEvidence = async (bytes: Uint8Array) =>
  sharp(bytes, {
    failOn: "warning",
    limitInputPixels: 40_000_000,
  })
    .rotate()
    .resize({
      width: 3_000,
      height: 3_000,
      fit: "inside",
      withoutEnlargement: true,
    })
    .webp({ quality: 88 })
    .toBuffer();

const decodeLegacyDataUrl = (value: string) => {
  const match =
    /^data:(image\/(?:jpeg|png|webp));base64,([a-z0-9+/=\r\n]+)$/i.exec(
      value,
    );
  if (!match) return null;

  const bytes = Buffer.from(match[2], "base64");
  if (bytes.length === 0 || bytes.length > MAX_EVIDENCE_BYTES) return null;

  return {
    bytes,
    contentType: match[1].toLowerCase(),
  };
};

const resolvePrivateEvidenceUrl = (value: string) => {
  const endpoint = config.env.imagekit.urlEndpoint;
  if (!endpoint) return null;

  try {
    const base = new URL(endpoint);
    const normalizedValue =
      value.startsWith("/") && !value.startsWith("//")
        ? value.slice(1)
        : value;
    const candidate = new URL(
      normalizedValue,
      `${base.toString().replace(/\/$/, "")}/`,
    );
    const basePath = base.pathname.replace(/\/$/, "");
    const allowedPrefixes = [
      `${basePath}/ids/`,
      `${basePath}/ids-quarantine/`,
    ];

    if (
      candidate.protocol !== "https:" ||
      candidate.origin !== base.origin ||
      !allowedPrefixes.some((prefix) =>
        candidate.pathname.startsWith(prefix),
      )
    ) {
      return null;
    }

    return candidate;
  } catch {
    return null;
  }
};

export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  try {
    const guard = await requireAdminCapabilityRouteAccess(
      "identity_evidence.read",
    );
    if (!guard.ok) return guard.response;

    const ip = await getClientIp();
    const limit = await ratelimit.limit(
      `identity-evidence:${guard.user.id}:${ip}`,
    );
    if (!limit.success) {
      return NextResponse.json(
        { error: "Too many identity-evidence requests" },
        { status: 429 },
      );
    }

    const { id } = await params;
    if (!isUuid(id)) {
      return NextResponse.json({ error: "Invalid user ID" }, { status: 400 });
    }

    const [record] = await db
      .select({ universityCard: users.universityCard })
      .from(users)
      .where(eq(users.id, id))
      .limit(1);

    if (!record) {
      return NextResponse.json({ error: "User not found" }, { status: 404 });
    }

    const legacyData = decodeLegacyDataUrl(record.universityCard);
    if (legacyData) {
      const normalized = await normalizeEvidence(legacyData.bytes);
      await logAdminAction(
        guard.user.id,
        "VIEW_IDENTITY_EVIDENCE",
        id,
        "USER",
        { source: "legacy-inline" },
        { mandatory: true },
      );
      return evidenceResponse(normalized, "image/webp");
    }

    const evidenceUrl = resolvePrivateEvidenceUrl(record.universityCard);
    if (!evidenceUrl) {
      logWarn("identity_evidence.reference_rejected", { userId: id });
      return NextResponse.json(
        { error: "Identity evidence is unavailable" },
        { status: 404 },
      );
    }

    const response = await fetch(evidenceUrl, {
      method: "GET",
      redirect: "error",
      signal: AbortSignal.timeout(5_000),
    });
    const declaredLength = Number(response.headers.get("content-length") ?? 0);
    const contentType =
      response.headers.get("content-type")?.split(";", 1)[0]?.toLowerCase() ??
      "";
    if (
      !response.ok ||
      !ALLOWED_IMAGE_TYPES.has(contentType) ||
      !Number.isFinite(declaredLength) ||
      declaredLength < 0 ||
      (declaredLength > 0 && declaredLength > MAX_EVIDENCE_BYTES)
    ) {
      return NextResponse.json(
        { error: "Identity evidence is unavailable" },
        { status: 502 },
      );
    }

    let bytes: Uint8Array;
    try {
      bytes = await readBoundedResponseBody(response, MAX_EVIDENCE_BYTES);
    } catch {
      return NextResponse.json(
        { error: "Identity evidence is unavailable" },
        { status: 502 },
      );
    }
    const normalized = await normalizeEvidence(bytes);

    await logAdminAction(
      guard.user.id,
      "VIEW_IDENTITY_EVIDENCE",
      id,
      "USER",
      { source: "managed-storage-proxy" },
      { mandatory: true },
    );

    return evidenceResponse(normalized, "image/webp");
  } catch (error) {
    logError("identity_evidence.fetch_failed", error);
    return NextResponse.json(
      { error: "Identity evidence could not be retrieved" },
      { status: 500 },
    );
  }
}
