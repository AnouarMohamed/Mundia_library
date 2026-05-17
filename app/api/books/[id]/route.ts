/**
 * Single Book API Route
 *
 * GET /api/books/[id]
 *
 * Purpose: Get a single book by its ID with all details.
 *
 * Route Parameters:
 * - id: Book ID (UUID)
 *
 * IMPORTANT: This route uses Node.js runtime (not Edge) because it needs database access
 */

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import { and, eq } from "drizzle-orm";
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
 * Get a single book by ID
 *
 * @param _request - Next.js request object
 * @param params - Route parameters containing book ID
 * @returns JSON response with book data
 */
export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    // This endpoint returns public book data (book details, not user-specific)
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

    // Fetch book by ID
    const [book] = await db
      .select()
      .from(books)
      .where(and(eq(books.id, id), eq(books.isActive, true)))
      .limit(1);

    if (!book) {
      return jsonError("Not Found", "Book not found", 404);
    }

    return NextResponse.json({
      success: true,
      book,
    });
  } catch (error) {
    logError("books.detail_failed", error);
    return internalServerErrorResponse();
  }
}
