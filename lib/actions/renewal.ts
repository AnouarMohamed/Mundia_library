/**
 * Renewal Management Server Actions
 * 
 * This module provides server actions for students to manage their book renewals.
 * It handles the lifecycle of a renewal request, including eligibility checks,
 * ownership verification, and persistence.
 * 
 * Key Operations:
 * - Submitting renewal requests for active borrowings.
 * - Checking eligibility for renewals to control UI visibility.
 */

"use server";

import { db } from "@/database/drizzle";
import { renewalRequests, borrowRecords } from "@/database/schema";
import { eq, and } from "drizzle-orm";
import { revalidateCatalogTags } from "@/lib/cache/revalidate";
import { requireApprovedUser } from "@/lib/security/auth-guards";
import { logError } from "@/lib/security/logger";

/**
 * Parameters required to submit a renewal request.
 */
export interface RequestRenewalParams {
  /** The unique ID of the original borrow record. */
  borrowRecordId: string;
  /** Optional explanation from the student for why they need the renewal. */
  reason?: string;
}

/**
 * Standardized response for renewal operations.
 */
export interface RenewalResponse {
  /** Indicates if the operation was successful. */
  success: boolean;
  /** Feedback message for the user on success. */
  message?: string;
  /** Error message for the user on failure. */
  error?: string;
}

/**
 * Submits a request to extend the due date of a currently borrowed book.
 * 
 * Business Logic Lifecycle:
 * 1. Authentication: Ensures the request is coming from a logged-in user.
 * 2. Ownership Verification: Confirms that the borrow record exists and actually
 *    belongs to the authenticated student.
 * 3. Status Check: Validates that the book is currently in the "BORROWED" state.
 *    Renewals cannot be requested for pending, returned, or cancelled loans.
 * 4. Duplicate Prevention: Ensures there isn't already a pending renewal request
 *    for this specific loan to avoid redundant administrative work.
 * 5. Persistence: Creates a new entry in the `renewalRequests` table.
 * 6. UI Synchronization: Triggers a revalidation of catalog tags to update the 
 *    user's profile view.
 * 
 * @param params - The borrow record ID and optional reason.
 * @returns A promise resolving to a success or error response object.
 */
export async function requestRenewal(params: RequestRenewalParams): Promise<RenewalResponse> {
  const { borrowRecordId, reason } = params;

  try {
    // 1. Authenticate the user
    const guard = await requireApprovedUser();
    if (!guard.ok) {
      return { success: false, error: guard.message };
    }

    const userId = guard.user.id;

    // 2. Validate the borrow record and ownership
    const [record] = await db
      .select({
        status: borrowRecords.status,
        userId: borrowRecords.userId,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.id, borrowRecordId))
      .limit(1);

    if (!record) {
      return { success: false, error: "Borrow record not found." };
    }

    if (record.userId !== userId) {
      return { success: false, error: "Unauthorized: This borrow record does not belong to you." };
    }

    // 3. Ensure the book is currently BORROWED
    if (record.status !== "BORROWED") {
      return { success: false, error: "Renewals can only be requested for active borrowings." };
    }

    // 4. Check for existing PENDING requests
    const [existingRequest] = await db
      .select()
      .from(renewalRequests)
      .where(
        and(
          eq(renewalRequests.borrowRecordId, borrowRecordId),
          eq(renewalRequests.status, "PENDING")
        )
      )
      .limit(1);

    if (existingRequest) {
      return { success: false, error: "A renewal request for this book is already pending approval." };
    }

    // 5. Create the renewal request record
    await db.insert(renewalRequests).values({
      borrowRecordId,
      userId: userId!,
      requestReason: reason,
      status: "PENDING",
    });

    // 6. Refresh UI data
    revalidateCatalogTags();

    return { 
      success: true, 
      message: "Renewal request submitted successfully. Please wait for admin approval." 
    };

  } catch (error) {
    logError("renewal.request_failed", error, { borrowRecordId });
    return { 
      success: false, 
      error: "An unexpected error occurred while processing your renewal request." 
    };
  }
}

/**
 * Checks if the current user is eligible to request a renewal for a specific loan.
 * 
 * This utility function is primarily used to control the visibility of the
 * "Request Renewal" button in the student's dashboard.
 * 
 * Eligibility Criteria:
 * - User must be authenticated.
 * - Borrow record must belong to the user.
 * - Current status of the loan must be "BORROWED".
 * - No other "PENDING" renewal requests must exist for this loan.
 * 
 * @param borrowRecordId - The ID of the borrow record to verify.
 * @returns A promise resolving to true if eligible, false otherwise.
 */
export async function canRequestRenewal(borrowRecordId: string): Promise<boolean> {
  try {
    const guard = await requireApprovedUser();
    if (!guard.ok) return false;

    const [record] = await db
      .select({
        status: borrowRecords.status,
        userId: borrowRecords.userId,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.id, borrowRecordId))
      .limit(1);

    // Basic ownership and status check
    if (!record || record.userId !== guard.user.id || record.status !== "BORROWED") {
      return false;
    }

    // Check for existing pending request
    const [pendingRequest] = await db
      .select({ id: renewalRequests.id })
      .from(renewalRequests)
      .where(
        and(
          eq(renewalRequests.borrowRecordId, borrowRecordId),
          eq(renewalRequests.status, "PENDING")
        )
      )
      .limit(1);

    return !pendingRequest;
  } catch (error) {
    logError("renewal.eligibility_failed", error, { borrowRecordId });
    return false;
  }
}
