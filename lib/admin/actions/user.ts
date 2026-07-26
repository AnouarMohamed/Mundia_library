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
import { adminUserColumns } from "@/lib/admin/user-projection";
import { isUuid } from "@/lib/security/api-request";

/**
 * Update a user's role.
 */
export const updateUserRole = async (
  userId: string,
  role: "USER" | "ADMIN"
) => {
  try {
    if (!new Set(["USER", "ADMIN"]).has(role)) {
      return { success: false, error: "Invalid role" };
    }
    if (!isUuid(userId)) {
      return { success: false, error: "Invalid user ID" };
    }

    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    if (guard.user.id === userId && role !== "ADMIN") {
      return {
        success: false,
        error: "You cannot remove your own administrator access",
      };
    }

    const updated = await db.transaction(async (tx) => {
      const currentAdmins = await tx
        .select({ id: users.id })
        .from(users)
        .where(eq(users.role, "ADMIN"))
        .for("update");

      if (
        role === "USER" &&
        currentAdmins.length === 1 &&
        currentAdmins[0]?.id === userId
      ) {
        throw new Error("The final administrator cannot be demoted");
      }

      const [changedUser] = await tx
        .update(users)
        .set({ role })
        .where(eq(users.id, userId))
        .returning({ id: users.id });

      return Boolean(changedUser);
    });

    if (!updated) {
      return { success: false, error: "User not found" };
    }

    await logAdminAction(guard.user.id, "UPDATE_USER_ROLE", userId, "USER", {
      role,
    });

    return { success: true };
  } catch (error) {
    logError("admin.user_role_update_failed", error, { userId, role });
    return {
      success: false,
      error:
        error instanceof Error &&
        error.message === "The final administrator cannot be demoted"
          ? error.message
          : "Failed to update user role",
    };
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
    if (!new Set(["PENDING", "APPROVED", "REJECTED"]).has(status)) {
      return { success: false, error: "Invalid status" };
    }
    if (!isUuid(userId)) {
      return { success: false, error: "Invalid user ID" };
    }

    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const [updatedUser] = await db
      .update(users)
      .set({ status })
      .where(eq(users.id, userId))
      .returning({ id: users.id });
    if (!updatedUser) {
      return { success: false, error: "User not found" };
    }

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
      .select(adminUserColumns)
      .from(users)
      .orderBy(desc(users.createdAt))
      .limit(100);

    return {
      success: true,
      data: allUsers.map((user) => ({
        ...user,
        // The evidence itself is fetched through an audited, admin-only route.
        universityCard: `/api/admin/users/${user.id}/identity-card`,
      })),
    };
  } catch (error) {
    logError("admin.users_fetch_failed", error);
    return { success: false, error: "Failed to fetch users" };
  }
};
