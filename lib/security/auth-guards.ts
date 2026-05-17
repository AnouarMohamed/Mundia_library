/**
 * Authentication Guards & Authorization Utilities
 * 
 * This module provides robust security guards for Next.js Route Handlers and Server Actions.
 * It enforces authentication and role-based access control (RBAC) by verifying 
 * the user's session and current database state.
 * 
 * Security Features:
 * - Session Validation: Ensures the user has an active, valid NextAuth session.
 * - Database Synchronization: Performs a "fresh" lookup in the database to ensure 
 *   role or status changes are reflected immediately, even if the JWT is still valid.
 * - Multi-tiered Protection:
 *   - requireUser: Basic authentication check.
 *   - requireApprovedUser: Checks if the user's account has been verified by an admin.
 *   - requireAdmin: Restricts access to administrators only.
 *   - requireSelfOrAdmin: Allows access if the user is an admin OR is accessing their own data.
 */

import { auth } from "@/auth";
import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { eq } from "drizzle-orm";
import {
  forbiddenResponse,
  unauthorizedResponse,
} from "@/lib/security/api-response";
import { logWarn } from "@/lib/security/logger";
import { NextResponse } from "next/server";

/** Valid roles for users within the application. */
export type AppRole = "USER" | "ADMIN";
/** Valid account lifecycle statuses. */
export type AccountStatus = "PENDING" | "APPROVED" | "REJECTED";

/**
 * Standardized authenticated user object returned by guards.
 */
export type AuthenticatedUser = {
  id: string;
  email?: string | null;
  name?: string | null;
  role: AppRole;
  status: AccountStatus;
  universityId?: number;
  universityCard?: string;
};

/** Result of a successful guard check. */
export type AuthGuardSuccess = {
  ok: true;
  session: Session;
  user: AuthenticatedUser;
};

/** Result of a failed guard check containing HTTP metadata. */
export type AuthGuardFailure = {
  ok: false;
  status: 401 | 403;
  error: string;
  message: string;
};

/** Union type representing all possible outcomes of an authentication guard. */
export type AuthGuardResult = AuthGuardSuccess | AuthGuardFailure;

/**
 * Internal helper to retrieve the most up-to-date user information from the database.
 * This prevents "stale session" vulnerabilities where a user's role/status is changed
 * in the DB but they still have a valid (unexpired) JWT.
 * 
 * @param userId - Unique identifier of the user.
 * @returns The user record or null if not found.
 */
const getFreshUserState = async (userId: string) => {
  const row = await db
    .select({
      id: users.id,
      email: users.email,
      fullName: users.fullName,
      role: users.role,
      status: users.status,
      universityId: users.universityId,
      universityCard: users.universityCard,
    })
    .from(users)
    .where(eq(users.id, userId))
    .limit(1);

  return row[0] ?? null;
};

/**
 * Converts a guard failure into a standard Next.js NextResponse.
 * Used primarily in Route Handlers.
 * 
 * @param guard - The failure result from a guard check.
 * @returns A NextResponse (401 or 403) with a JSON error payload.
 */
export const guardToResponse = (guard: AuthGuardFailure): NextResponse => {
  if (guard.status === 401) {
    return unauthorizedResponse();
  }

  return forbiddenResponse(guard.message);
};

/**
 * Basic authentication guard. Verifies the user has a valid session and exists in the DB.
 * 
 * @returns An AuthGuardResult (Success or Failure).
 */
export const requireUser = async (): Promise<AuthGuardResult> => {
  const session = await auth();

  // Step 1: Check session existence
  if (!session?.user?.id) {
    return {
      ok: false,
      status: 401,
      error: "Unauthorized",
      message: "Authentication required",
    };
  }

  // Step 2: Verify user still exists in DB
  const user = await getFreshUserState(session.user.id);

  if (!user) {
    logWarn("auth.user_missing", { userId: session.user.id });
    return {
      ok: false,
      status: 401,
      error: "Unauthorized",
      message: "Authentication required",
    };
  }

  return {
    ok: true,
    session,
    user: {
      id: user.id,
      email: user.email,
      name: user.fullName,
      role: user.role as AppRole,
      status: user.status as AccountStatus,
      universityId: user.universityId,
      universityCard: user.universityCard,
    },
  };
};

/**
 * Strict authentication guard. Verifies authentication AND that the account is 'APPROVED'.
 * This is the standard guard for most student-facing features.
 */
export const requireApprovedUser = async (): Promise<AuthGuardResult> => {
  const guard = await requireUser();

  if (!guard.ok) {
    return guard;
  }

  if (guard.user.status !== "APPROVED") {
    logWarn("auth.account_not_approved", {
      userId: guard.user.id,
      status: guard.user.status,
    });
    return {
      ok: false,
      status: 403,
      error: "Forbidden",
      message: "Account approval required",
    };
  }

  return guard;
};

/**
 * Administrative guard. Restricts access to users with the 'ADMIN' role.
 */
export const requireAdmin = async (): Promise<AuthGuardResult> => {
  const guard = await requireApprovedUser();

  if (!guard.ok) {
    return guard;
  }

  if (guard.user.role !== "ADMIN") {
    logWarn("auth.admin_denied", { userId: guard.user.id });
    return {
      ok: false,
      status: 403,
      error: "Forbidden",
      message: "Admin access required",
    };
  }

  return guard;
};

/**
 * Ownership or Administrative guard. 
 * Allows access if the user is an admin OR if they match the `targetUserId`.
 * 
 * @param targetUserId - The ID of the resource owner being accessed.
 */
export const requireSelfOrAdmin = async (
  targetUserId: string,
): Promise<AuthGuardResult> => {
  const guard = await requireApprovedUser();

  if (!guard.ok) {
    return guard;
  }

  if (guard.user.role !== "ADMIN" && guard.user.id !== targetUserId) {
    logWarn("auth.self_or_admin_denied", {
      userId: guard.user.id,
      targetUserId,
    });
    return {
      ok: false,
      status: 403,
      error: "Forbidden",
      message: "You can only access your own data",
    };
  }

  return guard;
};

/**
 * Formats a guard failure for consumption by Server Actions.
 * 
 * @param guard - The failed guard check.
 * @returns A standardized failure object compatible with Action responses.
 */
export const guardToActionError = (guard: AuthGuardFailure) => ({
  success: false as const,
  error: guard.message,
  message: guard.message,
});
