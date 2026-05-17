/**
 * Books Genres API Route
 *
 * GET /api/books/genres
 *
 * Purpose: Get a list of unique genres from all books.
 *
 * Returns: Array of unique genre strings
 *
 * IMPORTANT: This route uses Node.js runtime (not Edge) because it needs database access
 */

import { NextRequest, NextResponse } from "next/server";
import { unstable_cache } from "next/cache";
import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import { asc, eq } from "drizzle-orm";
import { enforceRateLimit } from "@/lib/security/api-request";
import {
  internalServerErrorResponse,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { logError } from "@/lib/security/logger";

/**
 * Use Node.js runtime for DB access.
 */
export const runtime = "nodejs";

/**
 * Cache distinct genres for the filter UI.
 */
const getCachedGenres = unstable_cache(
  async () => {
    return db
      .selectDistinct({ genre: books.genre })
      .from(books)
      .where(eq(books.isActive, true))
      .orderBy(asc(books.genre));
  },
  ["api-books-genres-v2"],
  { revalidate: 300, tags: ["books"] }
);

/**
 * Get unique genres from all books
 *
 * @param _request - Next.js request object (unused)
 * @returns JSON response with genres array
 */
export async function GET(_request: NextRequest) {
  try {
    // Rate limiting to prevent abuse
    const success = await enforceRateLimit();

    if (!success) {
      return tooManyRequestsResponse();
    }

    // Get unique genres from all books
    const genresResult = await getCachedGenres();

    const genres = genresResult.map((g) => g.genre).filter(Boolean);

    return NextResponse.json({
      success: true,
      genres,
    });
  } catch (error) {
    logError("books.genres_failed", error);
    return internalServerErrorResponse();
  }
}
