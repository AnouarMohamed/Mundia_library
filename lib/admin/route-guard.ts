import {
  AuthGuardResult,
  guardToResponse,
  requireAdmin,
} from "@/lib/security/auth-guards";
import {
  type AdminCapability,
  requireAdminCapability,
  requireSuperAdminCapability,
} from "@/lib/security/admin-capabilities";
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

/**
 * Require a fresh high-risk capability assignment for an admin API route.
 */
export async function requireAdminCapabilityRouteAccess(
  capability: AdminCapability,
): Promise<AdminRouteGuardResult> {
  const guard = await requireAdminCapability(capability);

  if (guard.ok) {
    return guard;
  }

  return {
    ok: false,
    response: guardToResponse(guard),
  };
}

/** Require super-admin governance authority for an API route. */
export async function requireSuperAdminCapabilityRouteAccess(
  capability: AdminCapability,
): Promise<AdminRouteGuardResult> {
  const guard = await requireSuperAdminCapability(capability);

  if (guard.ok) return guard;

  return { ok: false, response: guardToResponse(guard) };
}
