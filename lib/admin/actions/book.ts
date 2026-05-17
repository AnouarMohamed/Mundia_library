"use server";

import { randomUUID } from "crypto";
import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import { eq } from "drizzle-orm";
import { revalidateCatalogTags } from "@/lib/cache/revalidate";
import {
  guardToActionError,
  requireAdmin,
} from "@/lib/security/auth-guards";
import { logAdminAction } from "@/lib/admin/audit";
import { logError } from "@/lib/security/logger";

/**
 * Creates a new book record in the library catalog.
 * 
 * Logic:
 * 1. Generates a unique UUID for the book.
 * 2. Initializes `availableCopies` to match `totalCopies`.
 * 3. Persists the record to the database.
 * 4. Revalidates catalog cache tags to ensure the new book appears in the UI.
 * 
 * @param params - The book details including title, author, copies, etc.
 * @returns Object indicating success and the newly created book data, or an error.
 */
export const createBook = async (
  params: BookParams & { updatedBy?: string }
) => {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const bookId = randomUUID();

    await db
      .insert(books)
      .values({
        id: bookId,
        ...params,
        availableCopies: params.totalCopies, // Initially all copies are available
        updatedBy: guard.user.id,
        isActive: params.isActive ?? true, // Default to true if not provided
      });

    const newBook = await db
      .select()
      .from(books)
      .where(eq(books.id, bookId))
      .limit(1);

    revalidateCatalogTags();
    await logAdminAction(guard.user.id, "CREATE_BOOK", bookId, "BOOK", {
      title: params.title,
    });

    return {
      success: true,
      data: JSON.parse(JSON.stringify(newBook[0])),
    };
  } catch (error) {
    logError("admin.book_create_failed", error);

    return {
      success: false,
      message: "An error occurred while creating the book",
    };
  }
};

/**
 * Updates an existing book record in the catalog.
 * 
 * Special Logic for Inventory:
 * If `totalCopies` is updated, the system must maintain the correct `availableCopies`.
 * It calculates the number of currently borrowed books (`totalCopies` - `availableCopies`)
 * and adjusts the new `availableCopies` accordingly (`newTotal` - `borrowedCount`).
 * 
 * Flow:
 * 1. Fetches current book data if inventory change is requested.
 * 2. Calculates adjusted available copies.
 * 3. Updates the database record with the new values and timestamp.
 * 4. Triggers catalog cache revalidation.
 * 
 * @param bookId - The unique ID of the book to update.
 * @param params - Partial book details to be updated.
 * @returns Object indicating success and the updated book data, or an error.
 */
export const updateBook = async (
  bookId: string,
  params: Partial<BookParams> & { updatedBy?: string }
) => {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    // Inventory Adjustment Logic
    if (params.totalCopies) {
      // 1. Get current inventory state
      const currentBook = await db
        .select({
          totalCopies: books.totalCopies,
          availableCopies: books.availableCopies,
        })
        .from(books)
        .where(eq(books.id, bookId))
        .limit(1);

      if (currentBook.length === 0) {
        return {
          success: false,
          message: "Book not found",
        };
      }

      const currentData = currentBook[0];
      
      // 2. Calculate borrowed copies (delta)
      const borrowedCopies =
        currentData.totalCopies - currentData.availableCopies;
      
      // 3. Project new availability
      const newAvailableCopies = Math.max(
        0,
        params.totalCopies - borrowedCopies
      );

      await db
        .update(books)
        .set({
          ...params,
          availableCopies: newAvailableCopies,
          updatedBy: guard.user.id,
          updatedAt: new Date(),
        })
        .where(eq(books.id, bookId));

      const updatedBook = await db
        .select()
        .from(books)
        .where(eq(books.id, bookId))
        .limit(1);

      revalidateCatalogTags();
      await logAdminAction(guard.user.id, "UPDATE_BOOK", bookId, "BOOK", {
        totalCopies: params.totalCopies,
      });

      if (updatedBook.length === 0) {
        return {
          success: false,
          message: "Book not found after update",
        };
      }

      return {
        success: true,
        data: JSON.parse(JSON.stringify(updatedBook[0])),
      };
    } else {
      // Standard Metadata Update
      await db
        .update(books)
        .set({
          ...params,
          updatedBy: guard.user.id,
          updatedAt: new Date(),
        })
        .where(eq(books.id, bookId));

      const updatedBook = await db
        .select()
        .from(books)
        .where(eq(books.id, bookId))
        .limit(1);

      revalidateCatalogTags();
      await logAdminAction(guard.user.id, "UPDATE_BOOK", bookId, "BOOK", {
        fields: Object.keys(params),
      });

      if (updatedBook.length === 0) {
        return {
          success: false,
          message: "Book not found after update",
        };
      }

      return {
        success: true,
        data: JSON.parse(JSON.stringify(updatedBook[0])),
      };
    }
  } catch (error) {
    logError("admin.book_update_failed", error, { bookId });

    return {
      success: false,
      message: "An error occurred while updating the book",
    };
  }
};

/**
 * Retrieves a single book's full details by its ID.
 * 
 * @param bookId - The unique identifier of the book.
 * @returns Success status and book data, or failure message.
 */
export const getBookById = async (bookId: string) => {
  try {
    const guard = await requireAdmin();
    if (!guard.ok) return guardToActionError(guard);

    const book = await db
      .select()
      .from(books)
      .where(eq(books.id, bookId))
      .limit(1);

    if (book.length === 0) {
      return {
        success: false,
        message: "Book not found",
      };
    }

    return {
      success: true,
      data: JSON.parse(JSON.stringify(book[0])),
    };
  } catch (error) {
    logError("admin.book_fetch_failed", error, { bookId });

    return {
      success: false,
      message: "An error occurred while fetching the book",
    };
  }
};
