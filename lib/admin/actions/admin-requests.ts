"use server";

import { randomUUID } from "crypto";
import { db } from "@/database/drizzle";
import { adminRequests, auditLogs, users } from "@/database/schema";
import { eq, and, desc } from "drizzle-orm";
import {
  guardToActionError,
  requireSelfOrAdmin,
} from "@/lib/security/auth-guards";
import { requireAdminCapability } from "@/lib/security/admin-capabilities";
import { logAdminAction } from "@/lib/admin/audit";
import { logError } from "@/lib/security/logger";
import { isUuid } from "@/lib/security/api-request";

const adminRequestMutationErrors = new Set([
  "Admin request not found",
  "This request has already been processed",
  "User not found",
  "Administrators cannot approve their own access request",
  "Only approved users can become administrators",
]);

const safeAdminRequestMutationError = (error: unknown, fallback: string) =>
  error instanceof Error && adminRequestMutationErrors.has(error.message)
    ? error.message
    : fallback;

export interface AdminRequest {
  id: string;
  userId: string;
  userEmail: string;
  userFullName: string;
  requestReason: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewedBy: string | null | undefined;
  reviewedAt: Date | null | undefined;
  rejectionReason: string | null | undefined;
  createdAt: Date | null;
  updatedAt: Date | null;
}

export interface CreateAdminRequestResult {
  success: boolean;
  error?: string;
  data?: AdminRequest;
}

export interface GetAdminRequestsResult {
  success: boolean;
  error?: string;
  data?: AdminRequest[];
}

export interface UpdateAdminRequestResult {
  success: boolean;
  error?: string;
  data?: AdminRequest;
}

// Create a new admin request
/**
 * Create a new admin access request.
 */
export async function createAdminRequest(
  userId: string,
  requestReason: string
): Promise<CreateAdminRequestResult> {
  try {
    if (!isUuid(userId)) {
      return { success: false, error: "Invalid user ID" };
    }
    const normalizedReason = requestReason.trim();
    if (
      normalizedReason.length < 10 ||
      normalizedReason.length > 1_000
    ) {
      return {
        success: false,
        error: "Request reason must be between 10 and 1000 characters",
      };
    }

    const guard = await requireSelfOrAdmin(userId);
    if (!guard.ok) return guardToActionError(guard);

    // Check if user already has a pending admin request
    const existingRequest = await db
      .select()
      .from(adminRequests)
      .where(
        and(
          eq(adminRequests.userId, userId),
          eq(adminRequests.status, "PENDING")
        )
      )
      .limit(1);

    if (existingRequest.length > 0) {
      return {
        success: false,
        error: "You already have a pending admin request",
      };
    }

    // Check if user is already an admin
    const user = await db
      .select()
      .from(users)
      .where(eq(users.id, userId))
      .limit(1);

    if (user.length === 0) {
      return {
        success: false,
        error: "User not found",
      };
    }

    if (user[0].role === "ADMIN") {
      return {
        success: false,
        error: "You are already an admin",
      };
    }

    // Create the admin request
    const requestId = randomUUID();

    await db
      .insert(adminRequests)
      .values({
        id: requestId,
        userId,
        requestReason: normalizedReason,
        status: "PENDING",
      });

    // Get the full request with user details
    const fullRequest = await db
      .select({
        id: adminRequests.id,
        userId: adminRequests.userId,
        userEmail: users.email,
        userFullName: users.fullName,
        requestReason: adminRequests.requestReason,
        status: adminRequests.status,
        reviewedBy: adminRequests.reviewedBy,
        reviewedAt: adminRequests.reviewedAt,
        rejectionReason: adminRequests.rejectionReason,
        createdAt: adminRequests.createdAt,
        updatedAt: adminRequests.updatedAt,
      })
      .from(adminRequests)
      .innerJoin(users, eq(adminRequests.userId, users.id))
      .where(eq(adminRequests.id, requestId))
      .limit(1);

    return {
      success: true,
      data: fullRequest[0],
    };
  } catch (error) {
    logError("admin_request.create_failed", error, { userId });
    return {
      success: false,
      error: "Failed to create admin request",
    };
  }
}

// Get all admin requests (including approved and rejected)
/**
 * Fetch all admin requests.
 */
export async function getAllAdminRequests(): Promise<GetAdminRequestsResult> {
  try {
    const guard = await requireAdminCapability("roles.manage_admin");
    if (!guard.ok) return guardToActionError(guard);

    const requests = await db
      .select({
        id: adminRequests.id,
        userId: adminRequests.userId,
        userEmail: users.email,
        userFullName: users.fullName,
        requestReason: adminRequests.requestReason,
        status: adminRequests.status,
        reviewedBy: adminRequests.reviewedBy,
        reviewedAt: adminRequests.reviewedAt,
        rejectionReason: adminRequests.rejectionReason,
        createdAt: adminRequests.createdAt,
        updatedAt: adminRequests.updatedAt,
      })
      .from(adminRequests)
      .innerJoin(users, eq(adminRequests.userId, users.id))
      .orderBy(desc(adminRequests.createdAt))
      .limit(100);

    return {
      success: true,
      data: requests,
    };
  } catch (error) {
    logError("admin_request.fetch_all_failed", error);
    return {
      success: false,
      error: "Failed to fetch admin requests",
    };
  }
}

// Get only pending admin requests
/**
 * Fetch pending admin requests.
 */
export async function getPendingAdminRequests(): Promise<GetAdminRequestsResult> {
  try {
    const guard = await requireAdminCapability("roles.manage_admin");
    if (!guard.ok) return guardToActionError(guard);

    const requests = await db
      .select({
        id: adminRequests.id,
        userId: adminRequests.userId,
        userEmail: users.email,
        userFullName: users.fullName,
        requestReason: adminRequests.requestReason,
        status: adminRequests.status,
        reviewedBy: adminRequests.reviewedBy,
        reviewedAt: adminRequests.reviewedAt,
        rejectionReason: adminRequests.rejectionReason,
        createdAt: adminRequests.createdAt,
        updatedAt: adminRequests.updatedAt,
      })
      .from(adminRequests)
      .innerJoin(users, eq(adminRequests.userId, users.id))
      .where(eq(adminRequests.status, "PENDING"))
      .orderBy(desc(adminRequests.createdAt))
      .limit(100);

    return {
      success: true,
      data: requests,
    };
  } catch (error) {
    logError("admin_request.fetch_pending_failed", error);
    return {
      success: false,
      error: "Failed to fetch pending admin requests",
    };
  }
}

// Approve an admin request
/**
 * Approve an admin request and grant role.
 */
export async function approveAdminRequest(
  requestId: string,
  _reviewedBy: string
): Promise<UpdateAdminRequestResult> {
  try {
    if (!isUuid(requestId)) {
      return { success: false, error: "Invalid request ID" };
    }

    const guard = await requireAdminCapability("roles.manage_admin");
    if (!guard.ok) return guardToActionError(guard);

    const result = await db.transaction(async (tx) => {
      const [request] = await tx
        .select({
          userId: adminRequests.userId,
          status: adminRequests.status,
          userEmail: users.email,
          userFullName: users.fullName,
          userStatus: users.status,
        })
        .from(adminRequests)
        .innerJoin(users, eq(adminRequests.userId, users.id))
        .where(eq(adminRequests.id, requestId))
        .limit(1);

      if (!request) {
        throw new Error("Admin request not found");
      }

      if (request.status !== "PENDING") {
        throw new Error("This request has already been processed");
      }
      if (request.userId === guard.user.id) {
        throw new Error(
          "Administrators cannot approve their own access request",
        );
      }
      if (request.userStatus !== "APPROVED") {
        throw new Error("Only approved users can become administrators");
      }

      const reviewedAt = new Date();
      const [updatedRequest] = await tx
        .update(adminRequests)
        .set({
          status: "APPROVED",
          reviewedBy: guard.user.id,
          reviewedAt,
          updatedAt: reviewedAt,
        })
        .where(
          and(
            eq(adminRequests.id, requestId),
            eq(adminRequests.status, "PENDING"),
          ),
        )
        .returning({
          id: adminRequests.id,
          userId: adminRequests.userId,
          requestReason: adminRequests.requestReason,
          status: adminRequests.status,
          reviewedBy: adminRequests.reviewedBy,
          reviewedAt: adminRequests.reviewedAt,
          rejectionReason: adminRequests.rejectionReason,
          createdAt: adminRequests.createdAt,
          updatedAt: adminRequests.updatedAt,
        });

      if (!updatedRequest) {
        throw new Error("This request has already been processed");
      }

      const [promotedUser] = await tx
        .update(users)
        .set({ role: "ADMIN" })
        .where(eq(users.id, request.userId))
        .returning({ id: users.id });

      if (!promotedUser) {
        // Throwing rolls the request transition back with the role update.
        throw new Error("User not found");
      }

      await tx.insert(auditLogs).values({
        userId: guard.user.id,
        action: "APPROVE_ADMIN_REQUEST",
        targetId: requestId,
        targetType: "ADMIN_REQUEST",
        details: JSON.stringify({
          userId: request.userId,
          capabilitiesGranted: [],
        }),
      });

      return {
        userId: request.userId,
        request: {
          ...updatedRequest,
          userEmail: request.userEmail,
          userFullName: request.userFullName,
        },
      };
    });

    return {
      success: true,
      data: result.request,
    };
  } catch (error) {
    logError("admin_request.approve_failed", error, { requestId });
    return {
      success: false,
      error: safeAdminRequestMutationError(
        error,
        "Failed to approve admin request",
      ),
    };
  }
}

// Reject an admin request
/**
 * Reject an admin request.
 */
export async function rejectAdminRequest(
  requestId: string,
  _reviewedBy: string,
  rejectionReason?: string
): Promise<UpdateAdminRequestResult> {
  try {
    if (!isUuid(requestId)) {
      return { success: false, error: "Invalid request ID" };
    }
    const normalizedReason = rejectionReason?.trim();
    if (normalizedReason && normalizedReason.length > 1_000) {
      return { success: false, error: "Rejection reason is too long" };
    }

    const guard = await requireAdminCapability("roles.manage_admin");
    if (!guard.ok) return guardToActionError(guard);

    const result = await db.transaction(async (tx) => {
      const [request] = await tx
        .select({
          status: adminRequests.status,
          userEmail: users.email,
          userFullName: users.fullName,
        })
        .from(adminRequests)
        .innerJoin(users, eq(adminRequests.userId, users.id))
        .where(eq(adminRequests.id, requestId))
        .limit(1);

      if (!request) {
        throw new Error("Admin request not found");
      }

      if (request.status !== "PENDING") {
        throw new Error("This request has already been processed");
      }

      const reviewedAt = new Date();
      const [updatedRequest] = await tx
        .update(adminRequests)
        .set({
          status: "REJECTED",
          reviewedBy: guard.user.id,
          reviewedAt,
          rejectionReason: normalizedReason,
          updatedAt: reviewedAt,
        })
        .where(
          and(
            eq(adminRequests.id, requestId),
            eq(adminRequests.status, "PENDING"),
          ),
        )
        .returning({
          id: adminRequests.id,
          userId: adminRequests.userId,
          requestReason: adminRequests.requestReason,
          status: adminRequests.status,
          reviewedBy: adminRequests.reviewedBy,
          reviewedAt: adminRequests.reviewedAt,
          rejectionReason: adminRequests.rejectionReason,
          createdAt: adminRequests.createdAt,
          updatedAt: adminRequests.updatedAt,
        });

      if (!updatedRequest) {
        throw new Error("This request has already been processed");
      }

      return {
        ...updatedRequest,
        userEmail: request.userEmail,
        userFullName: request.userFullName,
      };
    });

    await logAdminAction(
      guard.user.id,
      "REJECT_ADMIN_REQUEST",
      requestId,
      "ADMIN_REQUEST",
      { reason: normalizedReason },
    );

    return {
      success: true,
      data: result,
    };
  } catch (error) {
    logError("admin_request.reject_failed", error, { requestId });
    return {
      success: false,
      error: safeAdminRequestMutationError(
        error,
        "Failed to reject admin request",
      ),
    };
  }
}

// Remove admin privileges from a user
/**
 * Remove admin role from a user.
 */
export async function removeAdminPrivileges(
  userId: string,
  _removedBy: string
): Promise<{ success: boolean; error?: string }> {
  const { updateUserRole } = await import("@/lib/admin/actions/user");
  return updateUserRole(userId, "USER");
}
