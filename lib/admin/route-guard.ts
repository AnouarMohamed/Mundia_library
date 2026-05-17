import {
  AuthGuardResult,
  guardToResponse,
  requireAdmin,
} from "@/lib/security/auth-guards";
import { NextResponse } from "next/server";

type GuardSuccess = Extract<AuthGuardResult, { ok: true }>;

type GuardFailure = {
  ok: false;
  response: NextResponse;
};

export type AdminRouteGuardResult = GuardSuccess | GuardFailure;

/**
 * Require an authenticated, approved admin session for API access.
 */
export async function requireAdminRouteAccess(): Promise<AdminRouteGuardResult> {
  const guard = await requireAdmin();

  if (guard.ok) {
    return guard;
  }

  return {
    ok: false,
    response: guardToResponse(guard),
  };
}
