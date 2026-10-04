import { NextResponse } from "next/server";
import { z } from "zod";
import { requireApprovedUser } from "@/lib/security/auth-guards";
import { getVerifiedLearningResource } from "@/lib/learning-resources/queries";

export const runtime = "nodejs";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const guard = await requireApprovedUser();
  if (!guard.ok) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  const id = z.string().uuid().safeParse((await params).id);
  if (!id.success) {
    return NextResponse.json({ error: "Invalid resource ID" }, { status: 400 });
  }
  const resource = await getVerifiedLearningResource(id.data);
  if (!resource?.downloadUrl) {
    return NextResponse.json({ error: "Download unavailable" }, { status: 404 });
  }
  let destination: URL;
  try {
    destination = new URL(resource.downloadUrl);
  } catch {
    return NextResponse.json({ error: "Download unavailable" }, { status: 404 });
  }
  if (destination.protocol !== "https:" || destination.username || destination.password) {
    return NextResponse.json({ error: "Download unavailable" }, { status: 404 });
  }
  const response = NextResponse.redirect(destination, 303);
  response.headers.set("Cache-Control", "private, no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}
