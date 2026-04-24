/**
 * Books API Route
 *
 * GET /api/books
 *
 * Purpose: Get a list of books with optional search, filters, sorting, and pagination.
 *
 * Query Parameters:
 * - search (optional): Search by title or author
 * - genre (optional): Filter by genre
 * - availability (optional): Filter by availability ("available" or "unavailable")
 * - rating (optional): Minimum rating (1-5)
 * - sort (optional): Sort order ("title", "author", "rating", "date")
 * - page (optional): Page number (default: 1)
 * - limit (optional): Books per page (default: 12)
 *
 * IMPORTANT: This route uses Node.js runtime (not Edge) because it needs database access
 */

import { NextRequest, NextResponse } from "next/server";
import { unstable_cache } from "next/cache";
import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import { desc, asc, eq, like, and, or, sql } from "drizzle-orm";
import { headers } from "next/headers";
import ratelimit from "@/lib/ratelimit";

export const runtime = "nodejs";

type BooksQueryInput = {
  search: string;
  genre: string;
  availability: string;
  rating: string;
  sort: string;
  page: number;
  limit: number;
};

const fetchBooksPage = async (input: BooksQueryInput) => {
  const whereConditions = [];

  whereConditions.push(eq(books.isActive, true));

  if (input.search) {
    const searchPattern = `%${input.search}%`;
    whereConditions.push(
      or(like(books.title, searchPattern), like(books.author, searchPattern))
    );
  }

  if (input.genre) {
    whereConditions.push(eq(books.genre, input.genre));
  }

  if (input.availability === "available") {
    whereConditions.push(sql`${books.availableCopies} > 0`);
  } else if (input.availability === "unavailable") {
    whereConditions.push(sql`${books.availableCopies} = 0`);
  }

  if (input.rating) {
    const minRating = parseInt(input.rating, 10);
    whereConditions.push(sql`${books.rating} >= ${minRating}`);
  }

  let orderBy;
  switch (input.sort) {
    case "author":
      orderBy = asc(books.author);
      break;
    case "rating":
      orderBy = desc(books.rating);
      break;
    case "date":
      orderBy = desc(books.createdAt);
      break;
    case "title":
    default:
      orderBy = asc(books.title);
      break;
  }

  const offset = (input.page - 1) * input.limit;
  const whereClause =
    whereConditions.length > 0 ? and(...whereConditions) : undefined;

  const [allBooks, totalBooksResult] = await Promise.all([
    db
      .select()
      .from(books)
      .where(whereClause)
      .orderBy(orderBy)
      .limit(input.limit)
      .offset(offset),
    db.select({ count: sql<number>`count(*)` }).from(books).where(whereClause),
  ]);

  const totalBooks = totalBooksResult[0]?.count || 0;
  const totalPages = Math.ceil(totalBooks / input.limit);

  return {
    books: allBooks,
    pagination: {
      currentPage: input.page,
      totalPages,
      totalBooks,
      booksPerPage: input.limit,
    },
  };
};

const getCachedBooksPage = unstable_cache(
  async (input: BooksQueryInput) => fetchBooksPage(input),
  ["api-books-v5"],
  {
    revalidate: 60,
    tags: ["books"],
  }
);

/**
 * Get books list with filters and pagination
 *
 * @param request - Next.js request object
 * @returns JSON response with books array and pagination info
 */
export async function GET(request: NextRequest) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    // This endpoint returns public book data (book list with filters, not user-specific)
    // Rate limiting provides protection against abuse while keeping it accessible for public book pages
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

    // Parse query parameters
    const search = searchParams.get("search") || "";
    const genre = searchParams.get("genre") || "";
    const availability = searchParams.get("availability") || "";
    const rating = searchParams.get("rating") || "";
    const sort = searchParams.get("sort") || "title";
    const page = parseInt(searchParams.get("page") || "1", 10);
    const limitParam = parseInt(searchParams.get("limit") || "12", 10);

    const safePage = Number.isNaN(page) ? 1 : Math.max(1, page);
    const safeLimit = Number.isNaN(limitParam)
      ? 12
      : Math.min(50, Math.max(1, limitParam));

    const result = await getCachedBooksPage({
      search,
      genre,
      availability,
      rating,
      sort,
      page: safePage,
      limit: safeLimit,
    });

    return NextResponse.json({
      success: true,
      books: result.books,
      pagination: result.pagination,
    });
  } catch (error) {
    console.error("Error fetching books:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch books",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
