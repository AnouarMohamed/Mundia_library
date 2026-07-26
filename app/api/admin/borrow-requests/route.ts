/**
 * Admin Borrow Requests API Route
 *
 * GET /api/admin/borrow-requests
 *
 * Purpose: Get all borrow requests with user and book details for admin management.
 *
 * Query Parameters:
 * - status (optional): Filter by status (PENDING, BORROWED, RETURNED)
 *
 * IMPORTANT: This route uses Node.js runtime (not Edge) because it needs database access
 */

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/database/drizzle";
import { borrowRecords, books, users } from "@/database/schema";
import { eq, desc, and, or, ilike, sql } from "drizzle-orm";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";
import ratelimit from "@/lib/ratelimit";
import {
  getClientIp,
  normalizeTextParam,
} from "@/lib/security/api-request";
import { logError } from "@/lib/security/logger";

/**
 * Use Node.js runtime for DB access.
 */
export const runtime = "nodejs";

/**
 * Get all borrow requests with user and book details (admin view)
 *
 * @param request - Next.js request object
 * @returns JSON response with borrow requests array
 */
export async function GET(request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const ip = await getClientIp();
    const rate = await ratelimit.limit(
      `admin-borrow-requests:${guard.user.id}:${ip}`,
    );
    if (!rate.success) {
      return NextResponse.json(
        { success: false, error: "Too Many Requests" },
        { status: 429 },
      );
    }

    const { searchParams } = new URL(request.url);

    // Parse query parameters
    const search = normalizeTextParam(searchParams.get("search"), 100);
    const status = searchParams.get("status");
    const requestedPage = Number.parseInt(searchParams.get("page") || "1", 10);
    const requestedLimit = Number.parseInt(
      searchParams.get("limit") || "50",
      10,
    );
    const page =
      Number.isSafeInteger(requestedPage) && requestedPage > 0
        ? requestedPage
        : 1;
    const limit =
      Number.isSafeInteger(requestedLimit) && requestedLimit > 0
        ? Math.min(requestedLimit, 100)
        : 50;
    const allowedStatuses = new Set([
      null,
      "",
      "PENDING",
      "BORROWED",
      "RETURNED",
    ]);
    if (!allowedStatuses.has(status)) {
      return NextResponse.json(
        { success: false, error: "Invalid status filter" },
        { status: 400 },
      );
    }

    // Build where conditions
    const whereConditions = [];

    // Status filter
    if (status) {
      whereConditions.push(
        eq(
          borrowRecords.status,
          status as "PENDING" | "BORROWED" | "RETURNED",
        ),
      );
    }

    // Search condition
    if (search) {
      const searchPattern = `%${search}%`;
      whereConditions.push(
        or(
          ilike(books.title, searchPattern),
          ilike(books.author, searchPattern),
          ilike(users.fullName, searchPattern),
          ilike(users.email, searchPattern),
          sql`CAST(${users.universityId} AS text) LIKE ${searchPattern}`
        )
      );
    }

    // Fetch borrow records with user and book details
    const whereClause =
      whereConditions.length > 0 ? and(...whereConditions) : undefined;
    const offset = (page - 1) * limit;
    const [allBorrowRecords, countResult] = await Promise.all([
      db.select({
        // Borrow record fields
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
        // User details
        userName: users.fullName,
        userEmail: users.email,
        userUniversityId: users.universityId,
        // Book details
        bookTitle: books.title,
        bookAuthor: books.author,
        bookGenre: books.genre,
        bookCoverUrl: books.coverUrl,
        bookCoverColor: books.coverColor,
      })
      .from(borrowRecords)
      .innerJoin(users, eq(borrowRecords.userId, users.id))
      .innerJoin(books, eq(borrowRecords.bookId, books.id))
      .where(whereClause)
      .orderBy(desc(borrowRecords.createdAt))
      .limit(limit)
      .offset(offset),
      db
        .select({ count: sql<number>`count(*)` })
        .from(borrowRecords)
        .innerJoin(users, eq(borrowRecords.userId, users.id))
        .innerJoin(books, eq(borrowRecords.bookId, books.id))
        .where(whereClause),
    ]);
    const total = countResult[0]?.count ?? 0;

    // Transform to BorrowRecordWithDetails format
    const requests = allBorrowRecords.map((record) => ({
      id: record.id,
      userId: record.userId,
      bookId: record.bookId,
      borrowDate: record.borrowDate,
      dueDate: record.dueDate,
      returnDate: record.returnDate,
      status: record.status,
      borrowedBy: record.borrowedBy,
      returnedBy: record.returnedBy,
      fineAmount: record.fineAmount,
      notes: record.notes,
      renewalCount: record.renewalCount,
      lastReminderSent: record.lastReminderSent,
      updatedAt: record.updatedAt,
      updatedBy: record.updatedBy,
      createdAt: record.createdAt,
      // User details
      userName: record.userName,
      userEmail: record.userEmail,
      userUniversityId: record.userUniversityId,
      // Book details
      bookTitle: record.bookTitle,
      bookAuthor: record.bookAuthor,
      bookGenre: record.bookGenre,
      bookCoverUrl: record.bookCoverUrl,
      bookCoverColor: record.bookCoverColor,
    }));

    return NextResponse.json({
      success: true,
      requests,
      total,
      page,
      totalPages: Math.ceil(total / limit),
      limit,
    });
  } catch (error) {
    logError("admin.borrow_requests_api_fetch_failed", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch borrow requests",
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
