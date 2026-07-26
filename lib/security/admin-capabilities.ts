import { and, eq, gt, inArray, isNull, or } from "drizzle-orm";

import { db } from "@/database/drizzle";
import {
  adminCapabilityAssignments,
  type AdminCapability,
} from "@/database/schema";
import {
  type AuthGuardFailure,
  type AuthGuardResult,
  requireAdmin,
} from "@/lib/security/auth-guards";
import { logWarn } from "@/lib/security/logger";

export type { AdminCapability } from "@/database/schema";

type RequiredCapabilities = readonly [
  AdminCapability,
  ...AdminCapability[],
];

const capabilityDenied = (): AuthGuardFailure => ({
  ok: false,
  status: 403,
  error: "Forbidden",
  message: "Required administrative capability is not assigned",
});

/**
 * Require an approved ADMIN and a fresh, active database assignment for every
 * requested high-risk capability.
 *
 * Capability state is deliberately not copied into the session JWT or cached.
 * A revoked or expired assignment therefore takes effect on the next request.
 * Database failures propagate as request failures rather than falling back to
 * the broad ADMIN role.
 */
export async function requireAdminCapabilities(
  required: RequiredCapabilities,
): Promise<AuthGuardResult> {
  const guard = await requireAdmin();
  if (!guard.ok) return guard;

  const uniqueRequired = Array.from(new Set(required));
  const now = new Date();
  const assignments = await db
    .select({ capability: adminCapabilityAssignments.capability })
    .from(adminCapabilityAssignments)
    .where(
      and(
        eq(adminCapabilityAssignments.userId, guard.user.id),
        inArray(adminCapabilityAssignments.capability, uniqueRequired),
        isNull(adminCapabilityAssignments.revokedAt),
        or(
          isNull(adminCapabilityAssignments.expiresAt),
          gt(adminCapabilityAssignments.expiresAt, now),
        ),
      ),
    );

  const assigned = new Set(assignments.map(({ capability }) => capability));
  const missing = uniqueRequired.filter(
    (capability) => !assigned.has(capability),
  );

  if (missing.length > 0) {
    logWarn("auth.admin_capability_denied", {
      userId: guard.user.id,
      missingCapabilities: missing,
    });
    return capabilityDenied();
  }

  return guard;
}

export async function requireAdminCapability(
  capability: AdminCapability,
): Promise<AuthGuardResult> {
  return requireAdminCapabilities([capability]);
}
