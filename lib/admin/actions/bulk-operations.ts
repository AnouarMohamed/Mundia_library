"use server";

import { db } from "@/database/drizzle";
import { books, users, borrowRecords } from "@/database/schema";
import { eq, sql, inArray, and } from "drizzle-orm";
import {
  guardToActionError,
  requireAdmin,
} from "@/lib/security/auth-guards";
import { logAdminAction } from "@/lib/admin/audit";
import { approveBorrowRequest, rejectBorrowRequest } from "@/lib/admin/actions/borrow";

// Bulk book operations
/**
 * Bulk update book fields.
 */
export async function bulkUpdateBooks(
  bookIds: string[],
  updates: Partial<typeof books.$inferInsert>
) {
  const guard = await requireAdmin();
  if (!guard.ok) return guardToActionError(guard);

  if (bookIds.length === 0) {
    return { success: false, message: "No books selected" };
  }

  try {
    await db
      .update(books)
      .set({
        ...updates,
        updatedAt: new Date(),
      })
      .where(inArray(books.id, bookIds));
    await logAdminAction(guard.user.id, "BULK_UPDATE_BOOKS", undefined, "BOOK", {
      bookIds,
      updates,
    });

    return {
      success: true,
      message: `Successfully updated ${bookIds.length} book(s)`,
    };
  } catch (error) {
    console.error("Failed to update books:", error);
    return {
      success: false,
      message: "Failed to update books",
    };
  }
}

/**
 * Bulk delete books after validation.
 */
export async function bulkDeleteBooks(bookIds: string[]) {
  const guard = await requireAdmin();
  if (!guard.ok) return guardToActionError(guard);

  if (bookIds.length === 0) {
    return { success: false, message: "No books selected" };
  }

  try {
    // Check if any books have active borrows
    const activeBorrows = await db
      .select({ count: sql<number>`count(*)` })
      .from(borrowRecords)
      .where(
        and(
          inArray(borrowRecords.bookId, bookIds),
          eq(borrowRecords.status, "BORROWED")
        )
      );

    if (activeBorrows[0]?.count > 0) {
      return {
        success: false,
        message: "Cannot delete books with active borrows",
      };
    }

    // Delete borrow records first
    await db
      .delete(borrowRecords)
      .where(inArray(borrowRecords.bookId, bookIds));

    // Delete books
    await db.delete(books).where(inArray(books.id, bookIds));
    await logAdminAction(guard.user.id, "BULK_DELETE_BOOKS", undefined, "BOOK", {
      bookIds,
    }, { mandatory: true });

    return {
      success: true,
      message: `Successfully deleted ${bookIds.length} book(s)`,
    };
  } catch (error) {
    console.error("Failed to delete books:", error);
    return {
      success: false,
      message: "Failed to delete books",
    };
  }
}

/**
 * Bulk activate books.
 */
export async function bulkActivateBooks(bookIds: string[]) {
  return bulkUpdateBooks(bookIds, { isActive: true });
}

/**
 * Bulk deactivate books.
 */
export async function bulkDeactivateBooks(bookIds: string[]) {
  return bulkUpdateBooks(bookIds, { isActive: false });
}

// Bulk user operations
/**
 * Bulk update user fields.
 */
export async function bulkUpdateUsers(
  userIds: string[],
  updates: Partial<typeof users.$inferInsert>
) {
  const guard = await requireAdmin();
  if (!guard.ok) return guardToActionError(guard);

  if (userIds.length === 0) {
    return { success: false, message: "No users selected" };
  }

  try {
    await db
      .update(users)
      .set({
        ...updates,
      })
      .where(inArray(users.id, userIds));
    await logAdminAction(guard.user.id, "BULK_UPDATE_USERS", undefined, "USER", {
      userIds,
      updates,
    });

    return {
      success: true,
      message: `Successfully updated ${userIds.length} user(s)`,
    };
  } catch (error) {
    console.error("Failed to update users:", error);
    return {
      success: false,
      message: "Failed to update users",
    };
  }
}

/**
 * Bulk approve users.
 */
export async function bulkApproveUsers(userIds: string[]) {
  return bulkUpdateUsers(userIds, { status: "APPROVED" });
}

/**
 * Bulk reject users.
 */
export async function bulkRejectUsers(userIds: string[]) {
  return bulkUpdateUsers(userIds, { status: "REJECTED" });
}

/**
 * Bulk grant admin role to users.
 */
export async function bulkMakeAdminUsers(userIds: string[]) {
  return bulkUpdateUsers(userIds, { role: "ADMIN" });
}

/**
 * Bulk remove admin role from users.
 */
export async function bulkRemoveAdminUsers(userIds: string[]) {
  return bulkUpdateUsers(userIds, { role: "USER" });
}

// Bulk borrow operations
/**
 * Bulk approve borrow requests.
 */
export async function bulkApproveBorrowRequests(recordIds: string[]) {
  const guard = await requireAdmin();
  if (!guard.ok) return guardToActionError(guard);

  if (recordIds.length === 0) {
    return { success: false, message: "No requests selected" };
  }

  try {
    const results = await Promise.all(
      recordIds.map((recordId) => approveBorrowRequest(recordId)),
    );
    const failures = results.filter((result) => !result.success);

    if (failures.length > 0) {
      return {
        success: false,
        message: `${failures.length} borrow request(s) could not be approved`,
      };
    }

    await logAdminAction(
      guard.user.id,
      "BULK_APPROVE_BORROW_REQUESTS",
      undefined,
      "BORROW_RECORD",
      { recordIds },
    );

    return {
      success: true,
      message: `Successfully approved ${recordIds.length} borrow request(s)`,
    };
  } catch (error) {
    console.error("Failed to approve requests:", error);
    return {
      success: false,
      message: "Failed to approve requests",
    };
  }
}

/**
 * Bulk reject borrow requests.
 */
export async function bulkRejectBorrowRequests(recordIds: string[]) {
  const guard = await requireAdmin();
  if (!guard.ok) return guardToActionError(guard);

  if (recordIds.length === 0) {
    return { success: false, message: "No requests selected" };
  }

  try {
    const results = await Promise.all(
      recordIds.map((recordId) => rejectBorrowRequest(recordId)),
    );
    const failures = results.filter((result) => !result.success);

    if (failures.length > 0) {
      return {
        success: false,
        message: `${failures.length} borrow request(s) could not be rejected`,
      };
    }

    await logAdminAction(
      guard.user.id,
      "BULK_REJECT_BORROW_REQUESTS",
      undefined,
      "BORROW_RECORD",
      { recordIds },
    );

    return {
      success: true,
      message: `Successfully rejected ${recordIds.length} borrow request(s)`,
    };
  } catch (error) {
    console.error("Failed to reject requests:", error);
    return {
      success: false,
      message: "Failed to reject requests",
    };
  }
}

// Get bulk operation statistics
/**
 * Fetch aggregate stats for bulk operations.
 */
export async function getBulkOperationStats() {
  const guard = await requireAdmin();
  if (!guard.ok) return guardToActionError(guard);

  const [totalBooks, totalUsers, pendingRequests, activeBorrows] =
    await Promise.all([
      db.select({ count: sql<number>`count(*)` }).from(books),
      db.select({ count: sql<number>`count(*)` }).from(users),
      db
        .select({ count: sql<number>`count(*)` })
        .from(borrowRecords)
        .where(eq(borrowRecords.status, "PENDING")),
      db
        .select({ count: sql<number>`count(*)` })
        .from(borrowRecords)
        .where(eq(borrowRecords.status, "BORROWED")),
    ]);

  return {
    totalBooks: totalBooks[0]?.count || 0,
    totalUsers: totalUsers[0]?.count || 0,
    pendingRequests: pendingRequests[0]?.count || 0,
    activeBorrows: activeBorrows[0]?.count || 0,
  };
}

// Validate bulk operations
/**
 * Validate a bulk book operation before execution.
 */
export async function validateBulkBookOperation(
  bookIds: string[],
  operation: string
) {
  const guard = await requireAdmin();
  if (!guard.ok) return { valid: false, message: guard.message };

  if (bookIds.length === 0) {
    return { valid: false, message: "No books selected" };
  }

  if (operation === "delete") {
    // Check for active borrows
    const activeBorrows = await db
      .select({ count: sql<number>`count(*)` })
      .from(borrowRecords)
      .where(
        and(
          inArray(borrowRecords.bookId, bookIds),
          eq(borrowRecords.status, "BORROWED")
        )
      );

    if (activeBorrows[0]?.count > 0) {
      return {
        valid: false,
        message: `${activeBorrows[0].count} book(s) have active borrows and cannot be deleted`,
      };
    }
  }

  return { valid: true, message: "Operation is valid" };
}

/**
 * Validate a bulk user operation before execution.
 */
export async function validateBulkUserOperation(
  userIds: string[],
  operation: string
) {
  const guard = await requireAdmin();
  if (!guard.ok) return { valid: false, message: guard.message };

  if (userIds.length === 0) {
    return { valid: false, message: "No users selected" };
  }

  if (operation === "delete") {
    // Check for active borrows
    const activeBorrows = await db
      .select({ count: sql<number>`count(*)` })
      .from(borrowRecords)
      .where(
        and(
          inArray(borrowRecords.userId, userIds),
          eq(borrowRecords.status, "BORROWED")
        )
      );

    if (activeBorrows[0]?.count > 0) {
      return {
        valid: false,
        message: `${activeBorrows[0].count} user(s) have active borrows and cannot be deleted`,
      };
    }
  }

  return { valid: true, message: "Operation is valid" };
}
