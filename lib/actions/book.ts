/**
 * Book Management Server Actions
 * 
 * This module handles server-side operations related to books, such as initiating 
 * borrow requests. It ensures business rules are followed (e.g., availability checks)
 * and manages cache revalidation to keep the UI in sync.
 */

"use server";

import { randomUUID } from "crypto";
import { db } from "@/database/drizzle";
import { books, borrowRecords } from "@/database/schema";
import { eq } from "drizzle-orm";
import { revalidateCatalogTags } from "@/lib/cache/revalidate";
import {
  guardToActionError,
  requireSelfOrAdmin,
} from "@/lib/security/auth-guards";
import { logError } from "@/lib/security/logger";

/**
 * Parameters required to initiate a book borrow request.
 */
export interface BorrowBookParams {
  /** The unique ID of the student borrowing the book. */
  userId: string;
  /** The unique ID of the book being borrowed. */
  bookId: string;
}

/**
 * Standardized response for the borrow book operation.
 */
export type BorrowBookResponse =
  | {
      /** Indicates the operation was successful. */
      success: true;
      /** The newly created borrow record(s). */
      data: Array<{
        id: string;
        userId: string;
        bookId: string;
        borrowDate: Date | null;
        dueDate: string | null;
        returnDate: string | null;
        status: "PENDING" | "BORROWED" | "RETURNED";
        borrowedBy: string | null;
        returnedBy: string | null;
        fineAmount: string | null;
        notes: string | null;
        renewalCount: number;
        lastReminderSent: Date | null;
        updatedAt: Date | null;
        updatedBy: string | null;
        createdAt: Date | null;
      }>;
    }
  | {
      /** Indicates the operation failed. */
      success: false;
      /** Error message describing why the request failed. */
      error: string;
    };

/**
 * Initiates a borrow request for a specific book.
 * 
 * Flow:
 * 1. Checks if the book exists and has available copies.
 * 2. Creates a new record in `borrowRecords` with a 'PENDING' status.
 * 3. Triggers catalog cache revalidation to reflect the new state.
 * 
 * Note: Available copies are NOT decremented at this stage. 
 * This happens only after an administrator approves the request.
 *
 * @param params - The userId and bookId for the request.
 * @returns A promise resolving to the created record or an error message.
 */
export const borrowBook = async (
  params: BorrowBookParams
): Promise<BorrowBookResponse> => {
  const { userId, bookId } = params;

  try {
    const guard = await requireSelfOrAdmin(userId);
    if (!guard.ok) {
      return guardToActionError(guard);
    }

    // 1. Availability check
    const book = await db
      .select({ availableCopies: books.availableCopies })
      .from(books)
      .where(eq(books.id, bookId))
      .limit(1);

    if (!book.length || book[0].availableCopies <= 0) {
      return {
        success: false,
        error: "Book is not available for borrowing",
      };
    }

    const recordId = randomUUID();

    // 2. Insert pending record
    await db
      .insert(borrowRecords)
      .values({
        id: recordId,
        userId,
        bookId,
        dueDate: null, // Set during admin approval
        status: "PENDING",
      });

    const [record] = await db
      .select()
      .from(borrowRecords)
      .where(eq(borrowRecords.id, recordId))
      .limit(1);

    if (!record) {
      return {
        success: false,
        error: "Failed to create borrow request",
      };
    }

    // 3. Cache maintenance
    revalidateCatalogTags();

    return {
      success: true,
      data: [record],
    };
  } catch (error: unknown) {
    logError("borrow.request_failed", error, { userId, bookId });

    return {
      success: false,
      error: "An error occurred while borrowing the book",
    };
  }
};
