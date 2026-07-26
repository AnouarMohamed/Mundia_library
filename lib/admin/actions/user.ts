"use server";

import { db } from "@/database/drizzle";
import {
  adminCapabilityAssignments,
  auditLogs,
  users,
} from "@/database/schema";
import { and, eq, desc, isNull, sql } from "drizzle-orm";
import {
  guardToActionError,
} from "@/lib/security/auth-guards";
import { requireAdminCapability } from "@/lib/security/admin-capabilities";
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

    const guard = await requireAdminCapability("roles.manage_admin");
    if (!guard.ok) return guardToActionError(guard);

    if (guard.user.id === userId && role !== "ADMIN") {
      return {
        success: false,
        error: "You cannot remove your own administrator access",
      };
    }

    const updated = await db.transaction(async (tx) => {
      // Serialize administrator lifecycle changes so concurrent demotions and
      // suspensions cannot each believe another approved administrator remains.
      await tx.execute(sql`select pg_advisory_xact_lock(71202501)`);

      const [target] = await tx
        .select({
          id: users.id,
          role: users.role,
          status: users.status,
        })
        .from(users)
        .where(eq(users.id, userId))
        .limit(1)
        .for("update");

      if (!target) return false;
      if (role === "ADMIN" && target.status !== "APPROVED") {
        throw new Error("Only approved users can be promoted");
      }

      const operationalAdmins = await tx
        .select({ id: users.id })
        .from(users)
        .where(
          and(eq(users.role, "ADMIN"), eq(users.status, "APPROVED")),
        )
        .for("update");

      if (
        role === "USER" &&
        target.role === "ADMIN" &&
        target.status === "APPROVED" &&
        operationalAdmins.length === 1
      ) {
        throw new Error("The final administrator cannot be demoted");
      }

      const [changedUser] = await tx
        .update(users)
        .set({ role })
        .where(eq(users.id, userId))
        .returning({ id: users.id });

      if (changedUser && role === "USER") {
        await tx
          .update(adminCapabilityAssignments)
          .set({
            revokedAt: new Date(),
            revokedBy: guard.user.id,
            revokeReason: "Administrative role removed",
          })
          .where(
            and(
              eq(adminCapabilityAssignments.userId, userId),
              isNull(adminCapabilityAssignments.revokedAt),
            ),
          );
      }

      if (changedUser) {
        await tx.insert(auditLogs).values({
          userId: guard.user.id,
          action: "UPDATE_USER_ROLE",
          targetId: userId,
          targetType: "USER",
          details: JSON.stringify({
            previousRole: target.role,
            role,
            capabilitiesRevoked: role === "USER",
          }),
        });
      }

      return Boolean(changedUser);
    });

    if (!updated) {
      return { success: false, error: "User not found" };
    }

    return { success: true };
  } catch (error) {
    logError("admin.user_role_update_failed", error, { userId, role });
    return {
      success: false,
      error:
        error instanceof Error &&
        new Set([
          "The final administrator cannot be demoted",
          "Only approved users can be promoted",
        ]).has(error.message)
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

    const guard = await requireAdminCapability("users.manage_status");
    if (!guard.ok) return guardToActionError(guard);

    if (guard.user.id === userId) {
      return {
        success: false,
        error: "You cannot change your own account status",
      };
    }

    const updated = await db.transaction(async (tx) => {
      await tx.execute(sql`select pg_advisory_xact_lock(71202501)`);

      const [target] = await tx
        .select({
          id: users.id,
          role: users.role,
          status: users.status,
        })
        .from(users)
        .where(eq(users.id, userId))
        .limit(1)
        .for("update");

      if (!target) return false;

      const operationalAdmins = await tx
        .select({ id: users.id })
        .from(users)
        .where(
          and(eq(users.role, "ADMIN"), eq(users.status, "APPROVED")),
        )
        .for("update");

      if (
        target.role === "ADMIN" &&
        target.status === "APPROVED" &&
        status !== "APPROVED" &&
        operationalAdmins.length === 1
      ) {
        throw new Error("The final administrator cannot be suspended");
      }

      const [changedUser] = await tx
        .update(users)
        .set({ status })
        .where(eq(users.id, userId))
        .returning({ id: users.id });

      if (
        changedUser &&
        target.role === "ADMIN" &&
        target.status === "APPROVED" &&
        status !== "APPROVED"
      ) {
        await tx
          .update(adminCapabilityAssignments)
          .set({
            revokedAt: new Date(),
            revokedBy: guard.user.id,
            revokeReason: "Administrator account status removed",
          })
          .where(
            and(
              eq(adminCapabilityAssignments.userId, userId),
              isNull(adminCapabilityAssignments.revokedAt),
            ),
          );
      }

      if (changedUser) {
        await tx.insert(auditLogs).values({
          userId: guard.user.id,
          action: "UPDATE_USER_STATUS",
          targetId: userId,
          targetType: "USER",
          details: JSON.stringify({
            previousStatus: target.status,
            status,
            capabilitiesRevoked:
              target.role === "ADMIN" && status !== "APPROVED",
          }),
        });
      }

      return Boolean(changedUser);
    });

    if (!updated) {
      return { success: false, error: "User not found" };
    }

    return { success: true };
  } catch (error) {
    logError("admin.user_status_update_failed", error, { userId, status });
    return {
      success: false,
      error:
        error instanceof Error &&
        error.message === "The final administrator cannot be suspended"
          ? error.message
          : "Failed to update user status",
    };
  }
};

/**
 * Fetch all users sorted by creation date.
 */
export const getAllUsers = async () => {
  try {
    const guard = await requireAdminCapability("users.manage_status");
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
