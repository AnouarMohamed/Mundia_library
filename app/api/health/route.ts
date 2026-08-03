/**
 * System Health API Endpoint
 * 
 * Provides a status report on the application's core infrastructure.
 * Checks connectivity to the primary database and verifies Redis configuration.
 * Used by monitoring tools and deployment pipelines to verify system readiness.
 * 
 * @module app/api/health/route
 */

import { NextResponse } from "next/server";
import { sql } from "drizzle-orm";
import { db } from "@/database/drizzle";
import redis from "@/database/redis";
import config from "@/lib/config";
import { logError } from "@/lib/security/logger";

/**
 * Force Node.js runtime for database connectivity checks.
 */
export const runtime = "nodejs";

/**
 * GET Handler for /api/health
 * 
 * Performs health checks on:
 * 1. Database: Executes a simple "SELECT 1" query.
 * 2. Cache: Verifies presence of Upstash Redis credentials.
 * 
 * @returns {NextResponse} JSON response containing health status and metadata
 */
export async function GET() {
  let database = false;
  let schema = false;
  let cache = false;
  const redisConfigured = Boolean(
    config.env.upstash.redisUrl && config.env.upstash.redisToken,
  );

  try {
    const result = await db.execute(
      sql<{ schemaReady: boolean }>`
        select (
          to_regclass('public.users') is not null
          and to_regclass('public.books') is not null
          and to_regclass('public.borrow_records') is not null
          and to_regclass('public.audit_logs') is not null
          and to_regclass('public.renewal_requests') is not null
          and to_regclass('public.notifications') is not null
          and to_regclass('public.rate_limit_buckets') is not null
          and exists (
            select 1
            from pg_constraint
            where conname = 'borrow_records_lifecycle_valid'
              and convalidated
          )
        ) as "schemaReady"
      `,
    );
    database = true;
    schema = result.rows[0]?.schemaReady === true;
  } catch (error) {
    logError("health.database_check_failed", error);
  }

  if (redisConfigured) {
    try {
      cache = (await redis.ping()) === "PONG";
    } catch (error) {
      logError("health.redis_check_failed", error);
    }
  }

  const rateLimiting =
    config.env.rateLimitBackend === "postgres"
      ? database && schema
      : cache;
  const protectedTier = ["staging", "production"].includes(
    config.env.appEnvironment,
  );
  const ready = database && schema && (!protectedTier || rateLimiting);

  // Aggregate health status metadata.
  const body = {
    ok: ready,
    database,
    schema,
    cache,
    redisConfigured,
    rateLimiting,
    // Report the current deployment commit SHA if available.
    commit:
      process.env.VERCEL_GIT_COMMIT_SHA || process.env.NEXT_PUBLIC_COMMIT_SHA,
    timestamp: new Date().toISOString(),
  };

  return NextResponse.json(body, { status: ready ? 200 : 503 });
}
