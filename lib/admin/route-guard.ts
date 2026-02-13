import { auth } from "@/auth";
import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { eq } from "drizzle-orm";
import { NextResponse } from "next/server";

type GuardSuccess = {
  ok: true;
  session: Session;
};

type GuardFailure = {
  ok: false;
  response: NextResponse;
};

export type AdminRouteGuardResult = GuardSuccess | GuardFailure;

/**
 * Auth guard used by admin API routes.
 *
 * Performance note:
 * - If role exists in session JWT, we trust it and skip DB lookup.
 * - We fallback to DB when role is missing OR stale (e.g., recently promoted user).
 */
export async function requireAdminRouteAccess(): Promise<AdminRouteGuardResult> {
  const session = await auth();

  if (!session?.user?.id) {
    return {
      ok: false,
      response: NextResponse.json(
        {
          success: false,
          error: "Unauthorized",
          message: "Authentication required",
        },
        { status: 401 }
      ),
    };
  }

  const roleFromSession = (session.user as { role?: string }).role;

  if (roleFromSession === "ADMIN") {
    return { ok: true, session };
  }

  // Fallback for sessions created before role was added to JWT,
  // or sessions where role changed after login.
  const user = await db
    .select({ role: users.role })
    .from(users)
    .where(eq(users.id, session.user.id))
    .limit(1);

  if (user[0]?.role === "ADMIN") {
    return { ok: true, session };
  }

  return {
    ok: false,
    response: NextResponse.json(
      {
        success: false,
        error: "Forbidden",
        message: "Admin access required",
      },
      { status: 403 }
    ),
  };
}
