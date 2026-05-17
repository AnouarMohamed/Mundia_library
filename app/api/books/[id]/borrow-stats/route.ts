/**
 * Book Borrow Statistics API Route
 *
 * GET /api/books/[id]/borrow-stats
 *
 * Purpose: Get borrow statistics for a specific book.
 *
 * Route Parameters:
 * - id: Book ID (UUID)
 *
 * Returns:
 * - totalBorrows: Total number of times this book has been borrowed
 * - activeBorrows: Number of currently active (BORROWED) borrows
 * - returnedBorrows: Number of successfully returned borrows
 *
 * IMPORTANT: This route uses Node.js runtime (not Edge) because it needs database access
 */

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/database/drizzle";
import { books, borrowRecords } from "@/database/schema";
import { and, eq, count, sql } from "drizzle-orm";
import { enforceRateLimit, isUuid } from "@/lib/security/api-request";
import {
  badRequestResponse,
  internalServerErrorResponse,
  jsonError,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { logError } from "@/lib/security/logger";

/**
 * Use Node.js runtime for DB access.
 */
export const runtime = "nodejs";

/**
 * Get borrow statistics for a specific book
 *
 * @param _request - Next.js request object
 * @param params - Route parameters containing book ID
 * @returns JSON response with borrow statistics
 */
export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    // This endpoint returns public book statistics (aggregate data, not user-specific)
    // Rate limiting provides protection against abuse while keeping it accessible for public book pages
    const success = await enforceRateLimit();

    if (!success) {
      return tooManyRequestsResponse();
    }

    const { id } = await params;

    if (!id) {
      return badRequestResponse("Book ID is required");
    }

    if (!isUuid(id)) {
      return badRequestResponse("Invalid book ID");
    }

    const [book] = await db
      .select({ id: books.id })
      .from(books)
      .where(and(eq(books.id, id), eq(books.isActive, true)))
      .limit(1);

    if (!book) {
      return jsonError("Not Found", "Book not found", 404);
    }

    // Get borrow records statistics for this book
    const borrowStats = await db
      .select({
        totalBorrows: count(),
        activeBorrows: sql<number>`count(case when ${borrowRecords.status} = 'BORROWED' then 1 end)`,
        returnedBorrows: sql<number>`count(case when ${borrowRecords.status} = 'RETURNED' then 1 end)`,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.bookId, id));

    const stats = borrowStats[0] || {
      totalBorrows: 0,
      activeBorrows: 0,
      returnedBorrows: 0,
    };

    return NextResponse.json({
      success: true,
      stats: {
        // Normalize numeric aggregates to plain numbers.
        totalBorrows: Number(stats.totalBorrows) || 0,
        activeBorrows: Number(stats.activeBorrows) || 0,
        returnedBorrows: Number(stats.returnedBorrows) || 0,
      },
    });
  } catch (error) {
    logError("books.borrow_stats_failed", error);
    return internalServerErrorResponse();
  }
}
