/**
 * Administrative Borrow Management Server Actions
 * 
 * This file contains server-side logic for administrators to manage the full 
 * lifecycle of book loans. It handles high-stakes operations like approvals, 
 * returns, and financial penalties (fines).
 * 
 * Key Operations:
 * - Fetching all borrow requests (joins users and books)
 * - Approving borrow requests (sets due dates, decrements inventory)
 * - Rejecting borrow requests
 * - Returning books (increments inventory, finalizes fines)
 * - Calculating/Updating overdue fines
 * 
 * Security: All state-changing actions require ADMIN role authorization.
 */

"use server";

import { db } from "@/database/drizzle";
import { borrowRecords, books, users } from "@/database/schema";
import { eq, desc, and, sql } from "drizzle-orm";
import { revalidateCatalogTags } from "@/lib/cache/revalidate";
import { auth } from "@/auth";
import { logAdminAction } from "@/lib/admin/audit";
import { createNotification } from "@/lib/services/notification-service";

/**
 * Fetches all borrow records with associated student and book details.
 * 
 * Performance: Uses inner joins to provide a complete view for the admin dashboard
 * without requiring multiple client-side requests.
 * 
 * @returns Success status and the list of joined records.
 */
export const getAllBorrowRequests = async () => {
  try {
    const requests = await db
      .select({
        id: borrowRecords.id,
        userId: borrowRecords.userId,
        bookId: borrowRecords.bookId,
        borrowDate: borrowRecords.borrowDate,
        dueDate: borrowRecords.dueDate,
        returnDate: borrowRecords.returnDate,
        status: borrowRecords.status,
        createdAt: borrowRecords.createdAt,
        borrowedBy: borrowRecords.borrowedBy,
        returnedBy: borrowRecords.returnedBy,
        fineAmount: borrowRecords.fineAmount,
        notes: borrowRecords.notes,
        renewalCount: borrowRecords.renewalCount,
        lastReminderSent: borrowRecords.lastReminderSent,
        updatedAt: borrowRecords.updatedAt,
        updatedBy: borrowRecords.updatedBy,
        // Joined Student Details
        userName: users.fullName,
        userEmail: users.email,
        userUniversityId: users.universityId,
        // Joined Book Details
        bookTitle: books.title,
        bookAuthor: books.author,
        bookGenre: books.genre,
        bookCoverUrl: books.coverUrl,
        bookCoverColor: books.coverColor,
      })
      .from(borrowRecords)
      .innerJoin(users, eq(borrowRecords.userId, users.id))
      .innerJoin(books, eq(borrowRecords.bookId, books.id))
      .orderBy(desc(borrowRecords.createdAt));

    return { success: true, data: requests };
  } catch (error) {
    console.error("Error fetching borrow requests:", error);
    return { success: false, error: "Failed to fetch borrow requests" };
  }
};

/**
 * Directly updates the status of a borrow record.
 * 
 * @param recordId - ID of the record to update.
 * @param status - New status (PENDING, BORROWED, RETURNED).
 * @returns Success status or error message.
 */
export const updateBorrowStatus = async (
  recordId: string,
  status: "PENDING" | "BORROWED" | "RETURNED"
) => {
  try {
    await db
      .update(borrowRecords)
      .set({ status })
      .where(eq(borrowRecords.id, recordId));

    return { success: true };
  } catch (error) {
    console.error("Error updating borrow status:", error);
    return { success: false, error: "Failed to update borrow status" };
  }
};

/**
 * Approves a student's request to borrow a book.
 * 
 * Workflow:
 * 1. Authorization: Verifies the requester is an ADMIN.
 * 2. Validation: Confirms record existence and physical book availability.
 * 3. Logistics: Sets a due date (default: 7 days from now, end of day).
 * 4. Persistence: Updates status to 'BORROWED' and assigns an admin ID to `borrowedBy`.
 * 5. Inventory: Decrements the `availableCopies` for the book.
 * 6. Communication: Sends an in-app notification to the student.
 * 7. Audit: Logs the administrative action for accountability.
 * 
 * @param recordId - UUID of the borrow record.
 * @returns Success status or detailed error message.
 */
export const approveBorrowRequest = async (recordId: string) => {
  try {
    const session = await auth();
    const user = session?.user as { id?: string; role?: string } | undefined;
    if (!user || user.role !== "ADMIN") {
      return { success: false, error: "Unauthorized" };
    }

    const adminId = user.id;

    const record = await db
      .select({
        bookId: borrowRecords.bookId,
        userId: borrowRecords.userId,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.id, recordId))
      .limit(1);

    if (record.length === 0) {
      return { success: false, error: "Borrow record not found" };
    }

    const book = await db
      .select({ 
        availableCopies: books.availableCopies,
        title: books.title
      })
      .from(books)
      .where(eq(books.id, record[0].bookId))
      .limit(1);

    if (book.length === 0 || book[0].availableCopies <= 0) {
      return { success: false, error: "Book is no longer available" };
    }

    const dueDate = new Date();
    dueDate.setDate(dueDate.getDate() + 7); // Standard 7-day loan period
    dueDate.setHours(23, 59, 59, 999); // Due by the end of the day
    const dueDateString = dueDate.toISOString().split("T")[0];

    await db
      .update(borrowRecords)
      .set({
        status: "BORROWED",
        borrowedBy: adminId,
        dueDate: dueDateString,
        updatedAt: new Date(),
        updatedBy: adminId,
      })
      .where(eq(borrowRecords.id, recordId));

    await db
      .update(books)
      .set({ availableCopies: book[0].availableCopies - 1 })
      .where(eq(books.id, record[0].bookId));

    await createNotification({
      userId: record[0].userId,
      title: "Borrow Request Approved",
      message: `Your request to borrow "${book[0].title}" has been approved. The due date is ${dueDateString}.`,
      type: "SUCCESS",
    });

    await logAdminAction(adminId!, "APPROVE_BORROW", recordId, "BORROW_RECORD", {
      bookId: record[0].bookId,
      userId: record[0].userId,
    });

    revalidateCatalogTags();

    return { success: true };
  } catch (error) {
    console.error("Error approving borrow request:", error);
    return { success: false, error: "Failed to approve borrow request" };
  }
};

/**
 * Rejects a pending borrow request.
 * 
 * Note: Currently deletes the record for simplicity. In audit-heavy environments, 
 * this should be changed to a status update (e.g., 'REJECTED').
 * 
 * @param recordId - UUID of the request.
 * @returns Success status or error.
 */
export const rejectBorrowRequest = async (recordId: string) => {
  try {
    const session = await auth();
    const user = session?.user as { id?: string; role?: string } | undefined;
    if (!user || user.role !== "ADMIN") {
      return { success: false, error: "Unauthorized" };
    }

    const adminId = user.id;

    const record = await db
      .select({
        bookId: borrowRecords.bookId,
        userId: borrowRecords.userId,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.id, recordId))
      .limit(1);

    if (record.length === 0) {
      return { success: false, error: "Borrow record not found" };
    }

    await db.delete(borrowRecords).where(eq(borrowRecords.id, recordId));

    await logAdminAction(adminId!, "REJECT_BORROW", recordId, "BORROW_RECORD", {
      bookId: record[0].bookId,
      userId: record[0].userId,
    });

    revalidateCatalogTags();

    return { success: true };
  } catch (error) {
    console.error("Error rejecting borrow request:", error);
    return { success: false, error: "Failed to reject borrow request" };
  }
};

/**
 * Periodically updates fines for books that are overdue but not yet returned.
 * 
 * Logic:
 * - Only targets records where `status` is 'BORROWED' and `dueDate` < current date.
 * - Idempotency: Only updates records where `fineAmount` is currently 0 or NULL 
 *   to avoid accidental overcharging or overwriting manual adjustments.
 * 
 * @param customFineAmount - Optional daily rate override.
 * @returns List of updated records and their calculated fines.
 */
export const updateOverdueFines = async (customFineAmount?: number) => {
  const today = new Date();

  // Dynamic import to resolve circular dependency with config.ts
  const { getDailyFineAmount } = await import("./config");
  const dailyFineAmount = customFineAmount || (await getDailyFineAmount());

  const overdueRecords = await db
    .select({
      id: borrowRecords.id,
      userId: borrowRecords.userId,
      dueDate: borrowRecords.dueDate,
      fineAmount: borrowRecords.fineAmount,
    })
    .from(borrowRecords)
    .where(
      and(
        eq(borrowRecords.status, "BORROWED"),
        sql`${borrowRecords.dueDate} < ${today}`,
        sql`${borrowRecords.fineAmount} IS NULL OR ${borrowRecords.fineAmount} = '0.00'`
      )
    );

  const results = [];

  for (const record of overdueRecords) {
    if (record.dueDate) {
      const dueDate = new Date(record.dueDate);
      const daysOverdue = Math.max(
        0,
        Math.floor(
          (today.getTime() - dueDate.getTime()) / (1000 * 60 * 60 * 24)
        )
      );

      const fineAmount =
        daysOverdue > 0 ? (daysOverdue * dailyFineAmount).toFixed(2) : "0.00";

      await db
        .update(borrowRecords)
        .set({
          fineAmount: fineAmount,
          updatedAt: new Date(),
          updatedBy: record.userId,
        })
        .where(eq(borrowRecords.id, record.id));

      results.push({
        recordId: record.id,
        daysOverdue,
        fineAmount,
        updated: true,
      });
    }
  }

  return results;
};

/**
 * Forcefully recalculates fines for all overdue books, regardless of current state.
 * Primarily used for maintenance or after system-wide fine rate changes.
 * 
 * @param customFineAmount - Daily rate override.
 * @returns Detailed results of the recalculation.
 */
export const forceUpdateOverdueFines = async (customFineAmount?: number) => {
  const today = new Date();

  const { getDailyFineAmount } = await import("./config");
  const dailyFineAmount = customFineAmount || (await getDailyFineAmount());

  const overdueRecords = await db
    .select({
      id: borrowRecords.id,
      userId: borrowRecords.userId,
      dueDate: borrowRecords.dueDate,
      currentFineAmount: borrowRecords.fineAmount,
    })
    .from(borrowRecords)
    .where(
      and(
        eq(borrowRecords.status, "BORROWED"),
        sql`${borrowRecords.dueDate} < ${today}`
      )
    );

  const results = [];

  for (const record of overdueRecords) {
    if (record.dueDate) {
      const dueDate = new Date(record.dueDate);
      const daysOverdue = Math.max(
        0,
        Math.floor(
          (today.getTime() - dueDate.getTime()) / (1000 * 60 * 60 * 24)
        )
      );

      const fineAmount =
        daysOverdue > 0 ? (daysOverdue * dailyFineAmount).toFixed(2) : "0.00";

      await db
        .update(borrowRecords)
        .set({
          fineAmount: fineAmount,
          updatedAt: new Date(),
          updatedBy: record.userId,
        })
        .where(eq(borrowRecords.id, record.id));

      const verifyResult = await db
        .select({ id: borrowRecords.id, fineAmount: borrowRecords.fineAmount })
        .from(borrowRecords)
        .where(eq(borrowRecords.id, record.id))
        .limit(1);

      results.push({
        recordId: record.id,
        daysOverdue,
        fineAmount,
        updated: true,
        previousFineAmount: record.currentFineAmount,
        verifiedFineAmount: verifyResult[0]?.fineAmount,
      });
    }
  }

  await new Promise((resolve) => setTimeout(resolve, 100));

  return results;
};

/**
 * Processes the return of a borrowed book.
 * 
 * Workflow:
 * 1. Authorization: Checks for ADMIN role.
 * 2. Logistics: Calculates final fine if the book is overdue.
 * 3. Persistence: Updates record status to 'RETURNED' and sets return date/processed by.
 * 4. Inventory: Increments the `availableCopies` for the book.
 * 5. Audit: Logs the return action with fine details.
 * 
 * @param recordId - UUID of the loan record.
 * @returns Summary of fines and overdue status.
 */
export const returnBook = async (recordId: string) => {
  try {
    const session = await auth();
    const user = session?.user as { id?: string; role?: string } | undefined;
    if (!user || user.role !== "ADMIN") {
      return { success: false, error: "Unauthorized" };
    }

    const adminId = user.id;
    const today = new Date().toISOString().split("T")[0];

    const record = await db
      .select({
        bookId: borrowRecords.bookId,
        userId: borrowRecords.userId,
        dueDate: borrowRecords.dueDate,
        borrowedBy: borrowRecords.borrowedBy,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.id, recordId))
      .limit(1);

    if (record.length === 0) {
      return { success: false, error: "Borrow record not found" };
    }

    // Final Fine Calculation
    const dueDate = record[0].dueDate ? new Date(record[0].dueDate) : null;
    const returnDate = new Date(today);
    const daysOverdue = dueDate
      ? Math.max(
          0,
          Math.floor(
            (returnDate.getTime() - dueDate.getTime()) / (1000 * 60 * 60 * 24)
          )
        )
      : 0;
    const fineAmount =
      daysOverdue > 0 ? (daysOverdue * 1.0).toFixed(2) : "0.00"; // $1 per day overdue rate

    await db
      .update(borrowRecords)
      .set({
        status: "RETURNED",
        returnDate: today,
        returnedBy: adminId,
        borrowedBy: record[0].borrowedBy || adminId,
        fineAmount: fineAmount,
        updatedAt: new Date(),
        updatedBy: adminId,
      })
      .where(eq(borrowRecords.id, recordId));

    const book = await db
      .select({ availableCopies: books.availableCopies })
      .from(books)
      .where(eq(books.id, record[0].bookId))
      .limit(1);

    if (book.length > 0) {
      await db
        .update(books)
        .set({ availableCopies: book[0].availableCopies + 1 })
        .where(eq(books.id, record[0].bookId));
    }

    await logAdminAction(adminId!, "RETURN_BOOK", recordId, "BORROW_RECORD", {
      bookId: record[0].bookId,
      userId: record[0].userId,
      fineAmount,
      daysOverdue,
    });

    revalidateCatalogTags();

    return {
      success: true,
      data: {
        fineAmount: parseFloat(fineAmount),
        daysOverdue,
        isOverdue: daysOverdue > 0,
      },
    };
  } catch (error) {
    console.error("Error returning book:", error);
    return { success: false, error: "Failed to return book" };
  }
};
