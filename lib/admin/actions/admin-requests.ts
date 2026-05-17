"use server";

import { randomUUID } from "crypto";
import { db } from "@/database/drizzle";
import { adminRequests, users } from "@/database/schema";
import { eq, and, desc } from "drizzle-orm";
import {
  guardToActionError,
  requireAdmin,
  requireSelfOrAdmin,
} from "@/lib/security/auth-guards";
import { logAdminAction } from "@/lib/admin/audit";
import { logError } from "@/lib/security/logger";

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
        requestReason,
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
    const guard = await requireAdmin();
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
      .orderBy(desc(adminRequests.createdAt));

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
    const guard = await requireAdmin();
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
      .orderBy(desc(adminRequests.createdAt));

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
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    // Get the request
    const request = await db
      .select()
      .from(adminRequests)
      .where(eq(adminRequests.id, requestId))
      .limit(1);

    if (request.length === 0) {
      return {
        success: false,
        error: "Admin request not found",
      };
    }

    if (request[0].status !== "PENDING") {
      return {
        success: false,
        error: "This request has already been processed",
      };
    }

    // Update the user's role to ADMIN
    await db
      .update(users)
      .set({ role: "ADMIN" })
      .where(eq(users.id, request[0].userId));

    // Update the admin request status
    await db
      .update(adminRequests)
      .set({
        status: "APPROVED",
        reviewedBy: guard.user.id,
        reviewedAt: new Date(),
      })
      .where(eq(adminRequests.id, requestId));

    // Get the full updated request with user details
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

    await logAdminAction(
      guard.user.id,
      "APPROVE_ADMIN_REQUEST",
      requestId,
      "ADMIN_REQUEST",
      { userId: request[0].userId },
    );

    return {
      success: true,
      data: fullRequest[0],
    };
  } catch (error) {
    logError("admin_request.approve_failed", error, { requestId });
    return {
      success: false,
      error: "Failed to approve admin request",
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
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    // Get the request
    const request = await db
      .select()
      .from(adminRequests)
      .where(eq(adminRequests.id, requestId))
      .limit(1);

    if (request.length === 0) {
      return {
        success: false,
        error: "Admin request not found",
      };
    }

    if (request[0].status !== "PENDING") {
      return {
        success: false,
        error: "This request has already been processed",
      };
    }

    // Update the admin request status
    await db
      .update(adminRequests)
      .set({
        status: "REJECTED",
        reviewedBy: guard.user.id,
        reviewedAt: new Date(),
        rejectionReason,
      })
      .where(eq(adminRequests.id, requestId));

    // Get the full updated request with user details
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

    await logAdminAction(
      guard.user.id,
      "REJECT_ADMIN_REQUEST",
      requestId,
      "ADMIN_REQUEST",
      { reason: rejectionReason },
    );

    return {
      success: true,
      data: fullRequest[0],
    };
  } catch (error) {
    logError("admin_request.reject_failed", error, { requestId });
    return {
      success: false,
      error: "Failed to reject admin request",
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
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    // Check if user exists and is an admin
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

    if (user[0].role !== "ADMIN") {
      return {
        success: false,
        error: "User is not an admin",
      };
    }

    // Update the user's role to USER
    await db.update(users).set({ role: "USER" }).where(eq(users.id, userId));
    await logAdminAction(
      guard.user.id,
      "REMOVE_ADMIN_PRIVILEGES",
      userId,
      "USER",
    );

    return {
      success: true,
    };
  } catch (error) {
    logError("admin_request.remove_admin_failed", error, { userId });
    return {
      success: false,
      error: "Failed to remove admin privileges",
    };
  }
}
