/**
 * Renewal Management Server Actions (Admin)
 * 
 * This file contains server actions for administrators to manage renewal requests.
 * These actions are designed to be modular, scalable, and heavily documented.
 * 
 * Key Operations:
 * - Fetching all renewal requests
 * - Approving a renewal request (extends due date)
 * - Rejecting a renewal request
 * 
 * Security:
 * - All actions require ADMIN role authentication.
 * - All sensitive actions are logged to the audit_logs table.
 */

"use server";

import { db } from "@/database/drizzle";
import { renewalRequests, borrowRecords, users, books } from "@/database/schema";
import { and, desc, eq, sql } from "drizzle-orm";
import { logAdminAction } from "@/lib/admin/audit";
import { revalidateCatalogTags } from "@/lib/cache/revalidate";
import { createNotification } from "@/lib/services/notification-service";
import {
  guardToActionError,
  requireAdmin,
} from "@/lib/security/auth-guards";
import { logError } from "@/lib/security/logger";

const renewalMutationErrors = new Set([
  "Renewal request not found.",
  "Associated borrow record not found.",
  "This renewal request has already been processed.",
  "Renewals can only be approved for active borrowings.",
]);

const safeRenewalMutationError = (error: unknown, fallback: string) =>
  error instanceof Error && renewalMutationErrors.has(error.message)
    ? error.message
    : fallback;

/**
 * Fetch all renewal requests with associated user and book information
 * 
 * @returns A promise resolving to a success/error response with the request data.
 */
export async function getAllRenewalRequests() {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const requests = await db
      .select({
        id: renewalRequests.id,
        status: renewalRequests.status,
        requestReason: renewalRequests.requestReason,
        createdAt: renewalRequests.createdAt,
        borrowRecordId: renewalRequests.borrowRecordId,
        // User info
        userName: users.fullName,
        userEmail: users.email,
        // Book info
        bookTitle: books.title,
        dueDate: borrowRecords.dueDate,
        renewalCount: borrowRecords.renewalCount,
      })
      .from(renewalRequests)
      .innerJoin(users, eq(renewalRequests.userId, users.id))
      .innerJoin(borrowRecords, eq(renewalRequests.borrowRecordId, borrowRecords.id))
      .innerJoin(books, eq(borrowRecords.bookId, books.id))
      .orderBy(desc(renewalRequests.createdAt))
      .limit(100);

    return { success: true, data: requests };
  } catch (error) {
    logError("admin.renewal_requests_fetch_failed", error);
    return { success: false, error: "Failed to load renewal requests." };
  }
}

/**
 * Approve a renewal request
 * 
 * Business Logic:
 * 1. Validate admin session.
 * 2. Fetch the request and associated borrow record.
 * 3. Calculate new due date (e.g., +7 days from CURRENT due date).
 * 4. Update the borrow record: new due date and increment renewalCount.
 * 5. Update the renewal request status to "APPROVED".
 * 6. Log the action to audit_logs.
 * 
 * @param requestId - The ID of the renewal request to approve.
 * @returns A promise resolving to a success or error response.
 */
export async function approveRenewal(requestId: string) {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const adminId = guard.user.id;

    const result = await db.transaction(async (tx) => {
      const [request] = await tx
        .select({
          borrowRecordId: renewalRequests.borrowRecordId,
          userId: renewalRequests.userId,
          status: renewalRequests.status,
        })
        .from(renewalRequests)
        .where(eq(renewalRequests.id, requestId))
        .limit(1);

      if (!request) {
        throw new Error("Renewal request not found.");
      }

      if (request.status !== "PENDING") {
        throw new Error("This renewal request has already been processed.");
      }

      const [record] = await tx
        .select({
          dueDate: borrowRecords.dueDate,
          status: borrowRecords.status,
          bookTitle: books.title,
        })
        .from(borrowRecords)
        .innerJoin(books, eq(borrowRecords.bookId, books.id))
        .where(eq(borrowRecords.id, request.borrowRecordId))
        .limit(1);

      if (!record) {
        throw new Error("Associated borrow record not found.");
      }

      if (record.status !== "BORROWED") {
        throw new Error("Renewals can only be approved for active borrowings.");
      }

      const currentDueDate = record.dueDate
        ? new Date(record.dueDate)
        : new Date();
      const newDueDate = new Date(currentDueDate);
      newDueDate.setDate(newDueDate.getDate() + 7);
      newDueDate.setHours(23, 59, 59, 999);
      const newDueDateString = newDueDate.toISOString().split("T")[0];

      const [approvedRequest] = await tx
        .update(renewalRequests)
        .set({
          status: "APPROVED",
          updatedAt: new Date(),
        })
        .where(
          and(
            eq(renewalRequests.id, requestId),
            eq(renewalRequests.status, "PENDING"),
          ),
        )
        .returning({ id: renewalRequests.id });

      if (!approvedRequest) {
        throw new Error("This renewal request has already been processed.");
      }

      const [renewedRecord] = await tx
        .update(borrowRecords)
        .set({
          dueDate: newDueDateString,
          renewalCount: sql`${borrowRecords.renewalCount} + 1`,
          updatedAt: new Date(),
          updatedBy: adminId,
        })
        .where(
          and(
            eq(borrowRecords.id, request.borrowRecordId),
            eq(borrowRecords.status, "BORROWED"),
          ),
        )
        .returning({ id: borrowRecords.id });

      if (!renewedRecord) {
        // Returning no row means a concurrent return won. Throwing also rolls
        // back the request transition performed earlier in this transaction.
        throw new Error("Renewals can only be approved for active borrowings.");
      }

      return {
        borrowRecordId: request.borrowRecordId,
        userId: request.userId,
        bookTitle: record.bookTitle,
        newDueDateString,
      };
    });

    // Send notification to the student
    await createNotification({
      userId: result.userId,
      title: "Renewal Approved",
      message: `Your renewal request for "${result.bookTitle}" has been approved. The new due date is ${result.newDueDateString}.`,
      type: "SUCCESS",
    });

    // 6. Log admin action
    await logAdminAction(adminId!, "APPROVE_RENEWAL", requestId, "RENEWAL_REQUEST", {
      borrowRecordId: result.borrowRecordId,
      newDueDate: result.newDueDateString,
    });

    await revalidateCatalogTags();

    return { success: true, message: "Renewal approved and due date extended." };
  } catch (error) {
    logError("admin.renewal_approve_failed", error, { requestId });
    return {
      success: false,
      error: safeRenewalMutationError(
        error,
        "Failed to approve renewal request.",
      ),
    };
  }
}

/**
 * Reject a renewal request
 * 
 * @param requestId - The ID of the renewal request to reject.
 * @param reason - Optional reason for rejection.
 * @returns A promise resolving to a success or error response.
 */
export async function rejectRenewal(requestId: string, reason?: string) {
  try {
    const normalizedReason = reason?.trim();
    if (normalizedReason && normalizedReason.length > 1_000) {
      return { success: false, error: "Rejection reason is too long." };
    }

    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const adminId = guard.user.id;

    const result = await db.transaction(async (tx) => {
      const [request] = await tx
        .select({
          borrowRecordId: renewalRequests.borrowRecordId,
          userId: renewalRequests.userId,
          status: renewalRequests.status,
          bookTitle: books.title,
        })
        .from(renewalRequests)
        .innerJoin(
          borrowRecords,
          eq(renewalRequests.borrowRecordId, borrowRecords.id),
        )
        .innerJoin(books, eq(borrowRecords.bookId, books.id))
        .where(eq(renewalRequests.id, requestId))
        .limit(1);

      if (!request) {
        throw new Error("Renewal request not found.");
      }

      if (request.status !== "PENDING") {
        throw new Error("This renewal request has already been processed.");
      }

      const [rejectedRequest] = await tx
        .update(renewalRequests)
        .set({
          status: "REJECTED",
          rejectionReason: normalizedReason,
          updatedAt: new Date(),
        })
        .where(
          and(
            eq(renewalRequests.id, requestId),
            eq(renewalRequests.status, "PENDING"),
          ),
        )
        .returning({ id: renewalRequests.id });

      if (!rejectedRequest) {
        throw new Error("This renewal request has already been processed.");
      }

      return request;
    });

    // Send notification to the student
    await createNotification({
      userId: result.userId,
      title: "Renewal Rejected",
      message: `Your renewal request for "${result.bookTitle}" has been rejected.${normalizedReason ? ` Reason: ${normalizedReason}` : ""}`,
      type: "WARNING",
    });

    // Log admin action
    await logAdminAction(adminId!, "REJECT_RENEWAL", requestId, "RENEWAL_REQUEST", {
      reason: normalizedReason,
    });

    await revalidateCatalogTags();

    return { success: true, message: "Renewal request rejected." };
  } catch (error) {
    logError("admin.renewal_reject_failed", error, { requestId });
    return {
      success: false,
      error: safeRenewalMutationError(
        error,
        "Failed to reject renewal request.",
      ),
    };
  }
}
