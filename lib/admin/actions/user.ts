"use server";

import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { eq, desc } from "drizzle-orm";
import {
  guardToActionError,
  requireAdmin,
} from "@/lib/security/auth-guards";
import { logAdminAction } from "@/lib/admin/audit";
import { logError } from "@/lib/security/logger";

/**
 * Update a user's role.
 */
export const updateUserRole = async (
  userId: string,
  role: "USER" | "ADMIN"
) => {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    await db.update(users).set({ role }).where(eq(users.id, userId));
    await logAdminAction(guard.user.id, "UPDATE_USER_ROLE", userId, "USER", {
      role,
    });

    return { success: true };
  } catch (error) {
    logError("admin.user_role_update_failed", error, { userId, role });
    return { success: false, error: "Failed to update user role" };
  }
};

/**
 * Update a user's approval status.
 */
export const updateUserStatus = async (
  userId: string,
  status: "PENDING" | "APPROVED" | "REJECTED"
) => {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    await db.update(users).set({ status }).where(eq(users.id, userId));
    await logAdminAction(guard.user.id, "UPDATE_USER_STATUS", userId, "USER", {
      status,
    });

    return { success: true };
  } catch (error) {
    logError("admin.user_status_update_failed", error, { userId, status });
    return { success: false, error: "Failed to update user status" };
  }
};

/**
 * Fetch all users sorted by creation date.
 */
export const getAllUsers = async () => {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const allUsers = await db
      .select()
      .from(users)
      .orderBy(desc(users.createdAt));

    return { success: true, data: allUsers };
  } catch (error) {
    logError("admin.users_fetch_failed", error);
    return { success: false, error: "Failed to fetch users" };
  }
};
