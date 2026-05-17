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
import { eq, desc } from "drizzle-orm";
import { logAdminAction } from "@/lib/admin/audit";
import { revalidateCatalogTags } from "@/lib/cache/revalidate";
import { createNotification } from "@/lib/services/notification-service";
import {
  guardToActionError,
  requireAdmin,
} from "@/lib/security/auth-guards";
import { logError } from "@/lib/security/logger";

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
      .orderBy(desc(renewalRequests.createdAt));

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
    // 1. Validate admin session
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const adminId = guard.user.id;

    // 2. Fetch request and record
    const [request] = await db
      .select({
        borrowRecordId: renewalRequests.borrowRecordId,
        userId: renewalRequests.userId,
      })
      .from(renewalRequests)
      .where(eq(renewalRequests.id, requestId))
      .limit(1);

    if (!request) return { success: false, error: "Renewal request not found." };

    const [record] = await db
      .select({
        dueDate: borrowRecords.dueDate,
        renewalCount: borrowRecords.renewalCount,
        bookTitle: books.title,
      })
      .from(borrowRecords)
      .innerJoin(books, eq(borrowRecords.bookId, books.id))
      .where(eq(borrowRecords.id, request.borrowRecordId))
      .limit(1);

    if (!record) return { success: false, error: "Associated borrow record not found." };

    // 3. Calculate new due date
    // If current due date exists, add 7 days to it. Otherwise, add 7 days from today.
    const currentDueDate = record.dueDate ? new Date(record.dueDate) : new Date();
    const newDueDate = new Date(currentDueDate);
    newDueDate.setDate(newDueDate.getDate() + 7);
    newDueDate.setHours(23, 59, 59, 999);
    const newDueDateString = newDueDate.toISOString().split("T")[0];

    // 4. Update borrow record
    await db
      .update(borrowRecords)
      .set({
        dueDate: newDueDateString,
        renewalCount: record.renewalCount + 1,
        updatedAt: new Date(),
        updatedBy: adminId,
      })
      .where(eq(borrowRecords.id, request.borrowRecordId));

    // 5. Update renewal request status
    await db
      .update(renewalRequests)
      .set({
        status: "APPROVED",
        updatedAt: new Date(),
      })
      .where(eq(renewalRequests.id, requestId));

    // Send notification to the student
    await createNotification({
      userId: request.userId,
      title: "Renewal Approved",
      message: `Your renewal request for "${record.bookTitle}" has been approved. The new due date is ${newDueDateString}.`,
      type: "SUCCESS",
    });

    // 6. Log admin action
    await logAdminAction(adminId!, "APPROVE_RENEWAL", requestId, "RENEWAL_REQUEST", {
      borrowRecordId: request.borrowRecordId,
      newDueDate: newDueDateString,
    });

    revalidateCatalogTags();

    return { success: true, message: "Renewal approved and due date extended." };
  } catch (error) {
    logError("admin.renewal_approve_failed", error, { requestId });
    return { success: false, error: "Failed to approve renewal request." };
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
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const adminId = guard.user.id;

    const [request] = await db
      .select({
        borrowRecordId: renewalRequests.borrowRecordId,
        userId: renewalRequests.userId,
        bookTitle: books.title,
      })
      .from(renewalRequests)
      .innerJoin(borrowRecords, eq(renewalRequests.borrowRecordId, borrowRecords.id))
      .innerJoin(books, eq(borrowRecords.bookId, books.id))
      .where(eq(renewalRequests.id, requestId))
      .limit(1);

    if (!request) return { success: false, error: "Renewal request not found." };

    // Update status to REJECTED
    await db
      .update(renewalRequests)
      .set({
        status: "REJECTED",
        rejectionReason: reason,
        updatedAt: new Date(),
      })
      .where(eq(renewalRequests.id, requestId));

    // Send notification to the student
    await createNotification({
      userId: request.userId,
      title: "Renewal Rejected",
      message: `Your renewal request for "${request.bookTitle}" has been rejected.${reason ? ` Reason: ${reason}` : ""}`,
      type: "WARNING",
    });

    // Log admin action
    await logAdminAction(adminId!, "REJECT_RENEWAL", requestId, "RENEWAL_REQUEST", {
      reason,
    });

    revalidateCatalogTags();

    return { success: true, message: "Renewal request rejected." };
  } catch (error) {
    logError("admin.renewal_reject_failed", error, { requestId });
    return { success: false, error: "Failed to reject renewal request." };
  }
}
