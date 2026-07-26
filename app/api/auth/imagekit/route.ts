import { NextResponse } from "next/server";

/**
 * Client-side ImageKit signing was intentionally retired. ImageKit signatures
 * do not bind folder, MIME type, or byte limits, so a public signature endpoint
 * grants broader upload capability than the UI claims.
 */
export async function GET() {
  return NextResponse.json(
    {
      error: "Direct upload signing has been retired. Use /api/uploads.",
    },
    { status: 410 },
  );
}
