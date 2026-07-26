/**
 * Borrow Records API Endpoint
 * 
 * Provides access to book borrowing history for users and administrators.
 * This endpoint enforces strict authorization:
 * - Regular users can only access their own records.
 * - Administrators can access all records and filter by any user.
 * 
 * @module app/api/borrow-records/route
 */

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/database/drizzle";
import { borrowRecords, books } from "@/database/schema";
import { eq, and, desc, asc, gte, lte, sql } from "drizzle-orm";
import {
  guardToResponse,
  requireApprovedUser,
} from "@/lib/security/auth-guards";
import {
  enforceRateLimit,
  isUuid,
  normalizeTextParam,
} from "@/lib/security/api-request";
import {
  badRequestResponse,
  forbiddenResponse,
  internalServerErrorResponse,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { logError } from "@/lib/security/logger";

/**
 * Force Node.js runtime for database connectivity.
 */
export const runtime = "nodejs";

// Input validation constants
const BORROW_STATUSES = new Set(["PENDING", "BORROWED", "RETURNED"]);
const SORT_OPTIONS = new Set(["date", "dueDate", "status", "user"]);

/**
 * Utility function to parse and validate date strings for filtering.
 * 
 * @param {string} value - The date string to parse (YYYY-MM-DD)
 * @param {string} label - Label for error messages
 * @returns {Object} Parsing result with status and date or error message
 */
const parseDateFilter = (value: string, label: string) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return { ok: false as const, message: `${label} must be YYYY-MM-DD` };
  }

  const parsed = new Date(`${value}T00:00:00.000Z`);

  if (
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== value
  ) {
    return { ok: false as const, message: `${label} must be a valid date` };
  }

  return { ok: true as const, date: parsed };
};

/**
 * GET Handler for /api/borrow-records
 * 
 * Expected Query Parameters:
 * - userId (UUID): Optional, filtered to authenticated user unless admin.
 * - bookId (UUID): Optional filter for a specific book.
 * - status (PENDING|BORROWED|RETURNED): Optional status filter.
 * - dateFrom (YYYY-MM-DD): Optional start date filter.
 * - dateTo (YYYY-MM-DD): Optional end date filter.
 * - overdue (boolean): Optional filter for overdue books.
 * - sort (date|dueDate|status|user): Optional sort field.
 * - page (number): Page index (starts at 1).
 * - limit (number): Results per page (max 100).
 * 
 * @param {NextRequest} request - Next.js Request object
 * @returns {NextResponse} JSON response containing borrow records and pagination
 */
export async function GET(request: NextRequest) {
  try {
    // 1. Rate Limiting: protect against automated scraping and abuse.
    const success = await enforceRateLimit();
    if (!success) {
      return tooManyRequestsResponse();
    }

    // 2. Authentication and Approval Guard.
    const guard = await requireApprovedUser();
    if (!guard.ok) {
      return guardToResponse(guard);
    }

    const { searchParams } = new URL(request.url);

    // 3. Input Normalization and Validation.
    const userId = normalizeTextParam(searchParams.get("userId"), 36) || undefined;
    const bookId = normalizeTextParam(searchParams.get("bookId"), 36) || undefined;
    const statusParam =
      normalizeTextParam(searchParams.get("status"), 20) || undefined;
    const dateFrom = normalizeTextParam(searchParams.get("dateFrom"), 10);
    const dateTo = normalizeTextParam(searchParams.get("dateTo"), 10);
    const overdueParam =
      normalizeTextParam(searchParams.get("overdue"), 5) || undefined;
    const sort = normalizeTextParam(searchParams.get("sort"), 20) || "date";
    const page = parseInt(searchParams.get("page") || "1", 10);
    const limitParam = parseInt(searchParams.get("limit") || "50", 10);

    const safePage = Number.isNaN(page) ? 1 : Math.max(1, page);
    const safeLimit = Number.isNaN(limitParam)
      ? 50
      : Math.min(100, Math.max(1, limitParam));

    const isAdmin = guard.user.role === "ADMIN";

    // UUID Validation for relational IDs.
    if (userId && !isUuid(userId)) {
      return badRequestResponse("Invalid user ID");
    }

    if (bookId && !isUuid(bookId)) {
      return badRequestResponse("Invalid book ID");
    }

    if (statusParam && !BORROW_STATUSES.has(statusParam)) {
      return badRequestResponse("Invalid borrow status");
    }

    if (overdueParam && !["true", "false"].includes(overdueParam)) {
      return badRequestResponse("Invalid overdue filter");
    }

    if (!SORT_OPTIONS.has(sort)) {
      return badRequestResponse("Invalid sort option");
    }

    const parsedDateFrom = dateFrom
      ? parseDateFilter(dateFrom, "dateFrom")
      : undefined;
    if (parsedDateFrom && !parsedDateFrom.ok) {
      return badRequestResponse(parsedDateFrom.message);
    }

    const parsedDateTo = dateTo ? parseDateFilter(dateTo, "dateTo") : undefined;
    if (parsedDateTo && !parsedDateTo.ok) {
      return badRequestResponse(parsedDateTo.message);
    }

    /**
     * CRITICAL SECURITY CHECK:
     * Users are restricted to their own records.
     * Only admins can query records for other users.
     */
    const finalUserId = isAdmin ? userId : guard.user.id;

    if (!isAdmin && userId && userId !== guard.user.id) {
      return forbiddenResponse("You can only access your own borrow records");
    }

    // 4. Build database query conditions.
    const whereConditions = [];

    if (finalUserId) {
      whereConditions.push(eq(borrowRecords.userId, finalUserId));
    }

    if (bookId) {
      whereConditions.push(eq(borrowRecords.bookId, bookId));
    }

    if (statusParam) {
      whereConditions.push(
        eq(
          borrowRecords.status,
          statusParam as "PENDING" | "BORROWED" | "RETURNED"
        )
      );
    }

    if (parsedDateFrom?.ok) {
      whereConditions.push(gte(borrowRecords.borrowDate, parsedDateFrom.date));
    }

    if (parsedDateTo?.ok) {
      whereConditions.push(lte(borrowRecords.borrowDate, parsedDateTo.date));
    }

    // Filter for overdue books: status is BORROWED and due date has passed.
    if (overdueParam === "true") {
      whereConditions.push(
        and(
          eq(borrowRecords.status, "BORROWED"),
          sql`${borrowRecords.dueDate} < CURRENT_DATE`
        )
      );
    }

    // Define sort order.
    let orderBy;
    switch (sort) {
      case "dueDate":
        orderBy = desc(borrowRecords.dueDate);
        break;
      case "status":
        orderBy = asc(borrowRecords.status);
        break;
      case "user":
        orderBy = asc(borrowRecords.userId);
        break;
      case "date":
      default:
        orderBy = desc(borrowRecords.createdAt);
        break;
    }

    // 5. Execute paginated join query to fetch records with book details.
    const offset = (safePage - 1) * safeLimit;
    const whereClause =
      whereConditions.length > 0 ? and(...whereConditions) : undefined;
    
    const [allBorrowRecords, totalRecordsResult] = await Promise.all([
      db
        .select({
          // Selecting specific fields from borrowRecords and books
          id: borrowRecords.id,
          userId: borrowRecords.userId,
          bookId: borrowRecords.bookId,
          borrowDate: borrowRecords.borrowDate,
          dueDate: borrowRecords.dueDate,
          returnDate: borrowRecords.returnDate,
          status: borrowRecords.status,
          borrowedBy: borrowRecords.borrowedBy,
          returnedBy: borrowRecords.returnedBy,
          fineAmount: borrowRecords.fineAmount,
          notes: borrowRecords.notes,
          renewalCount: borrowRecords.renewalCount,
          lastReminderSent: borrowRecords.lastReminderSent,
          updatedAt: borrowRecords.updatedAt,
          updatedBy: borrowRecords.updatedBy,
          createdAt: borrowRecords.createdAt,
          book: {
            id: books.id,
            title: books.title,
            author: books.author,
            genre: books.genre,
            rating: books.rating,
            totalCopies: books.totalCopies,
            availableCopies: books.availableCopies,
            description: books.description,
            coverColor: books.coverColor,
            coverUrl: books.coverUrl,
            videoUrl: books.videoUrl,
            summary: books.summary,
            isbn: books.isbn,
            publicationYear: books.publicationYear,
            publisher: books.publisher,
            language: books.language,
            pageCount: books.pageCount,
            edition: books.edition,
            isActive: books.isActive,
            createdAt: books.createdAt,
            updatedAt: books.updatedAt,
            updatedBy: books.updatedBy,
          },
        })
        .from(borrowRecords)
        .innerJoin(books, eq(borrowRecords.bookId, books.id))
        .where(whereClause)
        .orderBy(orderBy)
        .limit(safeLimit)
        .offset(offset),
      db
        .select({ count: sql<number>`count(*)` })
        .from(borrowRecords)
        .where(whereClause),
    ]);

    const totalRecords = totalRecordsResult[0]?.count || 0;
    const totalPages = Math.ceil(totalRecords / safeLimit);

    return NextResponse.json({
      success: true,
      borrows: allBorrowRecords,
      total: totalRecords,
      page: safePage,
      totalPages,
      limit: safeLimit,
      pagination: {
        currentPage: safePage,
        totalPages,
        totalRecords,
        recordsPerPage: safeLimit,
      },
    });
  } catch (error) {
    logError("borrow_records.fetch_failed", error);
    return internalServerErrorResponse();
  }
}
