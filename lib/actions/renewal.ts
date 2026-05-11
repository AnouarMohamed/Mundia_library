/**
 * Renewal Management Server Actions (Student)
 * 
 * This file contains server actions for students to manage their book renewals.
 * These actions are designed to be modular, scalable, and heavily documented.
 * 
 * Key Operations:
 * - Requesting a book renewal
 * - Checking renewal eligibility
 */

"use server";

import { db } from "@/database/drizzle";
import { renewalRequests, borrowRecords } from "@/database/schema";
import { eq, and } from "drizzle-orm";
import { auth } from "@/auth";
import { revalidateCatalogTags } from "@/lib/cache/revalidate";

/**
 * Interface for renewal request parameters
 */
export interface RequestRenewalParams {
  borrowRecordId: string;
  reason?: string;
}

/**
 * Interface for renewal request response
 */
export interface RenewalResponse {
  success: boolean;
  message?: string;
  error?: string;
}

/**
 * Request a renewal for a currently borrowed book
 * 
 * Business Logic:
 * 1. Authenticate the user.
 * 2. Validate the borrow record exists and belongs to the user.
 * 3. Ensure the book is currently in "BORROWED" status.
 * 4. Check if there's already a "PENDING" renewal request for this record.
 * 5. Create a new renewal request record.
 * 
 * @param params - The borrow record ID and optional reason for renewal.
 * @returns A promise resolving to a success or error response.
 */
/**
 * Submit a renewal request for a borrow record.
 */
export async function requestRenewal(params: RequestRenewalParams): Promise<RenewalResponse> {
  const { borrowRecordId, reason } = params;

  try {
    // 1. Authenticate the user
    const session = await auth();
    if (!session?.user) {
      return { success: false, error: "You must be logged in to request a renewal." };
    }

    const userId = session.user.id;

    // 2. Validate the borrow record
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

    // Ensure the record belongs to the authenticated user
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

    // 5. Create the renewal request
    await db.insert(renewalRequests).values({
      borrowRecordId,
      userId: userId!,
      requestReason: reason,
      status: "PENDING",
    });

    // Revalidate tags to update the UI
    revalidateCatalogTags();

    return { 
      success: true, 
      message: "Renewal request submitted successfully. Please wait for admin approval." 
    };

  } catch (error) {
    console.error("Error requesting renewal:", error);
    return { 
      success: false, 
      error: "An unexpected error occurred while processing your renewal request." 
    };
  }
}

/**
 * Check if a user is eligible to request a renewal for a specific book
 * 
 * Useful for conditionally rendering the "Request Renewal" button in the UI.
 * 
 * @param borrowRecordId - The ID of the borrow record to check.
 * @returns A promise resolving to a boolean indicating eligibility.
 */
/**
 * Check if the current user can request renewal.
 */
export async function canRequestRenewal(borrowRecordId: string): Promise<boolean> {
  try {
    const session = await auth();
    if (!session?.user) return false;

    const [record] = await db
      .select({
        status: borrowRecords.status,
        userId: borrowRecords.userId,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.id, borrowRecordId))
      .limit(1);

    if (!record || record.userId !== session.user.id || record.status !== "BORROWED") {
      return false;
    }

    // Check if there's already a pending request
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
    console.error("Error checking renewal eligibility:", error);
    return false;
  }
}
