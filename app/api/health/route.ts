import { NextResponse } from "next/server";
import { sql } from "drizzle-orm";
import { db } from "@/database/drizzle";
import config from "@/lib/config";
import { logError } from "@/lib/security/logger";

export const runtime = "nodejs";

export async function GET() {
  let database = false;

  try {
    await db.execute(sql`select 1`);
    database = true;
  } catch (error) {
    logError("health.database_check_failed", error);
  }

  const body = {
    ok: database,
    database,
    redisConfigured: Boolean(
      config.env.upstash.redisUrl && config.env.upstash.redisToken,
    ),
    commit:
      process.env.VERCEL_GIT_COMMIT_SHA || process.env.NEXT_PUBLIC_COMMIT_SHA,
    timestamp: new Date().toISOString(),
  };

  return NextResponse.json(body, { status: database ? 200 : 503 });
}
