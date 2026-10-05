import { NextResponse } from "next/server";
import { z } from "zod";
import { requireApprovedUser } from "@/lib/security/auth-guards";
import { getVerifiedLearningResource } from "@/lib/learning-resources/queries";
import {
  isProxiableNistDownload,
  MAX_PROXIED_NIST_BYTES,
} from "@/lib/learning-resources/nist";

export const runtime = "nodejs";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const guard = await requireApprovedUser();
  if (!guard.ok) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  const id = z
    .string()
    .uuid()
    .safeParse((await params).id);
  if (!id.success) {
    return NextResponse.json({ error: "Invalid resource ID" }, { status: 400 });
  }
  const resource = await getVerifiedLearningResource(id.data);
  if (!resource?.downloadUrl) {
    return NextResponse.json(
      { error: "Download unavailable" },
      { status: 404 },
    );
  }
  let destination: URL;
  try {
    destination = new URL(resource.downloadUrl);
  } catch {
    return NextResponse.json(
      { error: "Download unavailable" },
      { status: 404 },
    );
  }
  if (
    destination.protocol !== "https:" ||
    destination.username ||
    destination.password
  ) {
    return NextResponse.json(
      { error: "Download unavailable" },
      { status: 404 },
    );
  }
  if (isProxiableNistDownload(resource)) {
    return proxySmallNistPdf(destination);
  }
  const response = NextResponse.redirect(destination, 303);
  response.headers.set("Cache-Control", "private, no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}

async function proxySmallNistPdf(destination: URL) {
  try {
    const upstream = await fetch(destination, {
      headers: {
        Accept: "application/pdf",
        "User-Agent": "Mundia-Library/0.2 (+https://mundialibrary.tech)",
      },
      redirect: "error",
      signal: AbortSignal.timeout(20_000),
    });
    const contentType = (
      upstream.headers.get("content-type") ?? ""
    ).toLowerCase();
    const contentLength = Number(upstream.headers.get("content-length") ?? 0);
    if (
      !upstream.ok ||
      !contentType.startsWith("application/pdf") ||
      !Number.isSafeInteger(contentLength) ||
      contentLength < 1 ||
      contentLength > MAX_PROXIED_NIST_BYTES
    ) {
      return NextResponse.json(
        { error: "Download unavailable" },
        { status: 502 },
      );
    }
    const body = await readBoundedBody(upstream, MAX_PROXIED_NIST_BYTES);
    if (!body || body.byteLength < 1) {
      return NextResponse.json(
        { error: "Download unavailable" },
        { status: 502 },
      );
    }
    const filename = safePdfFilename(destination.pathname);
    return new NextResponse(body, {
      headers: {
        "Cache-Control": "private, no-store",
        "Content-Disposition": `attachment; filename="${filename}"`,
        "Content-Length": String(body.byteLength),
        "Content-Type": "application/pdf",
        "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff",
      },
    });
  } catch {
    return NextResponse.json(
      { error: "Download unavailable" },
      { status: 502 },
    );
  }
}

async function readBoundedBody(response: Response, limit: number) {
  if (!response.body) return null;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > limit) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

function safePdfFilename(pathname: string) {
  const value = pathname.split("/").at(-1) ?? "nist-publication.pdf";
  return /^[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.pdf$/u.test(value)
    ? value
    : "nist-publication.pdf";
}
