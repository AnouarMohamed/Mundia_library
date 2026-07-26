import { NextResponse } from "next/server";

/**
 * Process-only liveness probe. It deliberately does not call PostgreSQL,
 * Redis, or third parties; dependency outages must remove readiness without
 * causing an orchestrator restart loop.
 */
export async function GET() {
  return NextResponse.json(
    { ok: true },
    {
      status: 200,
      headers: {
        "Cache-Control": "no-store",
      },
    },
  );
}
