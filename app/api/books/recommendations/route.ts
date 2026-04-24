/**
 * Book Recommendations API Route
 *
 * GET /api/books/recommendations
 *
 * Purpose: Get personalized book recommendations for a user based on their reading history.
 *
 * Query Parameters:
 * - userId (optional): User ID for personalized recommendations
 * - limit (optional): Maximum number of recommendations (default: 10)
 *
 * Algorithm:
 * 1. If user has reading history, recommend books from similar genres/authors
 * 2. Exclude books the user has already borrowed
 * 3. If not enough recommendations, fill with high-rated books
 * 4. If no user or no history, return latest high-rated books
 *
 * IMPORTANT: This route uses Node.js runtime (not Edge) because it needs database access
 */

import { NextRequest, NextResponse } from "next/server";
import { unstable_cache } from "next/cache";
import { db } from "@/database/drizzle";
import { books, borrowRecords } from "@/database/schema";
import { desc, eq, sql, and, inArray, notInArray } from "drizzle-orm";
import { auth } from "@/auth";
import { headers } from "next/headers";
import ratelimit from "@/lib/ratelimit";

export const runtime = "nodejs";

const getFallbackRecommendations = unstable_cache(
  async (limit: number) => {
    return (await db
      .select()
      .from(books)
      .where(eq(books.isActive, true))
      .orderBy(desc(books.rating), desc(books.createdAt))
      .limit(limit)) as Book[];
  },
  ["api-books-recommendations-fallback-v2"],
  { revalidate: 90, tags: ["books", "recommendations"] }
);

const getPersonalizedRecommendations = unstable_cache(
  async (userId: string, limit: number) => {
    let recommendedBooks: Book[] = [];

    const userBorrowHistory = await db
      .select({
        genre: books.genre,
        author: books.author,
      })
      .from(borrowRecords)
      .innerJoin(books, eq(borrowRecords.bookId, books.id))
      .where(
        and(eq(borrowRecords.userId, userId), eq(borrowRecords.status, "RETURNED"))
      )
      .orderBy(desc(borrowRecords.borrowDate))
      .limit(10);

    if (userBorrowHistory.length === 0) {
      return [] as Book[];
    }

    const userBorrowedBookIds = await db
      .select({ bookId: borrowRecords.bookId })
      .from(borrowRecords)
      .where(eq(borrowRecords.userId, userId));

    const borrowedIds = userBorrowedBookIds.map((record) => record.bookId);
    const userGenres = [...new Set(userBorrowHistory.map((h) => h.genre))];

    const genreRecommendations = await db
      .select()
      .from(books)
      .where(
        and(
          inArray(books.genre, userGenres),
          borrowedIds.length > 0 ? notInArray(books.id, borrowedIds) : sql`1=1`,
          eq(books.isActive, true)
        )
      )
      .orderBy(desc(books.rating), desc(books.createdAt))
      .limit(limit);

    recommendedBooks = genreRecommendations as Book[];

    if (recommendedBooks.length < limit) {
      const remainingSlots = limit - recommendedBooks.length;
      const additionalBooks = await db
        .select()
        .from(books)
        .where(
          and(
            borrowedIds.length > 0 ? notInArray(books.id, borrowedIds) : sql`1=1`,
            eq(books.isActive, true)
          )
        )
        .orderBy(desc(books.rating), desc(books.createdAt))
        .limit(remainingSlots);

      const existingIds = recommendedBooks.map((book) => book.id);
      const uniqueAdditionalBooks = additionalBooks.filter(
        (book) => !existingIds.includes(book.id)
      );

      recommendedBooks = [...recommendedBooks, ...uniqueAdditionalBooks].slice(
        0,
        limit
      );
    }

    return recommendedBooks;
  },
  ["api-books-recommendations-user-v2"],
  { revalidate: 90, tags: ["books", "recommendations"] }
);

/**
 * Get book recommendations for a user
 *
 * @param request - Next.js request object
 * @returns JSON response with recommended books array
 */
export async function GET(request: NextRequest) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    // This endpoint returns personalized recommendations (if user is logged in) or high-rated books (if anonymous)
    // Rate limiting provides protection against abuse while keeping it accessible for public pages
    const ip = (await headers()).get("x-forwarded-for") || "127.0.0.1";
    const { success } = await ratelimit.limit(ip);

    if (!success) {
      return NextResponse.json(
        {
          success: false,
          error: "Too Many Requests",
          message: "Rate limit exceeded. Please try again later.",
        },
        { status: 429 }
      );
    }

    const { searchParams } = new URL(request.url);
    const userId = searchParams.get("userId") || undefined;
    const limitParam = parseInt(searchParams.get("limit") || "10", 10);
    const limit = Number.isNaN(limitParam)
      ? 10
      : Math.min(20, Math.max(1, limitParam));

    // Get session to determine user if userId not provided
    // Note: Authentication is optional - works for both authenticated and anonymous users
    const session = await auth();
    const finalUserId = userId || session?.user?.id;

    let recommendedBooks: Book[] = [];

    if (finalUserId) {
      recommendedBooks = await getPersonalizedRecommendations(finalUserId, limit);
    }

    if (recommendedBooks.length === 0) {
      recommendedBooks = await getFallbackRecommendations(limit);
    }

    return NextResponse.json({
      success: true,
      recommendations: recommendedBooks,
    });
  } catch (error) {
    console.error("Error fetching book recommendations:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch book recommendations",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
