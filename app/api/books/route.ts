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
import { desc, asc, eq, and, sql } from "drizzle-orm";
import { performAdvancedSearch } from "@/lib/services/search-service";
import { getCachedData, setCachedData } from "@/lib/cache/redis-cache";
import {
  enforceRateLimit,
  normalizeTextParam,
} from "@/lib/security/api-request";
import {
  badRequestResponse,
  internalServerErrorResponse,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { logError } from "@/lib/security/logger";

/**
 * Use Node.js runtime for DB access and Redis caching.
 */
export const runtime = "nodejs";

const MAX_SEARCH_LENGTH = 100;
const MAX_GENRE_LENGTH = 120;
const SORT_OPTIONS = new Set(["title", "author", "rating", "date"]);
const AVAILABILITY_OPTIONS = new Set(["", "available", "unavailable"]);

type BooksQueryInput = {
  search: string;
  genre: string;
  availability: string;
  rating: string;
  sort: string;
  page: number;
  limit: number;
};

interface BooksResult {
  books: Book[]; 
  pagination: {
    currentPage: number;
    totalPages: number;
    totalBooks: number;
    booksPerPage: number;
  };
}

/**
 * Fetch a page of books with optional search, filters, and caching.
 */
const fetchBooksPage = async (input: BooksQueryInput) => {
  const cacheKey = `books:list:${JSON.stringify(input)}`;

  // 1. Try to get from Redis cache
  const { data: cachedResult, isStale } =
    await getCachedData<BooksResult>(cacheKey);

  if (cachedResult && !isStale) {
    return cachedResult;
  }

  // 2. Fetch from DB (either first time or background refresh)
  const fetchFromDb = async () => {
    const offset = (input.page - 1) * input.limit;

    let result: BooksResult;
    if (input.search) {
      const searchSortBy = (
        ["relevance", "title", "rating", "date"].includes(input.sort)
          ? input.sort
          : "relevance"
      ) as "relevance" | "title" | "rating" | "date";

      const { books: searchedBooks, total } = await performAdvancedSearch({
        query: input.search,
        genre: input.genre || undefined,
        limit: input.limit,
        offset,
        sortBy: searchSortBy,
      });

      result = {
        books: searchedBooks,
        pagination: {
          currentPage: input.page,
          totalPages: Math.ceil(total / input.limit),
          totalBooks: total,
          booksPerPage: input.limit,
        },
      };
    } else {
      const whereConditions = [eq(books.isActive, true)];

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
        db
          .select({ count: sql<number>`count(*)` })
          .from(books)
          .where(whereClause),
      ]);

      const totalBooks = totalBooksResult[0]?.count || 0;
      result = {
        books: allBooks,
        pagination: {
          currentPage: input.page,
          totalPages: Math.ceil(totalBooks / input.limit),
          totalBooks,
          booksPerPage: input.limit,
        },
      };
    }

    // Cache the result (5 minutes fresh, 1 hour stale)
    await setCachedData(cacheKey, result, { ttl: 300, swr: 3600 });
    return result;
  };

  if (cachedResult && isStale) {
    // SWR: return stale and refresh in background
    fetchFromDb().catch(console.error);
    return cachedResult;
  }

  return await fetchFromDb();
};

/**
 * Next.js cache wrapper for the book list query.
 */
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
    const success = await enforceRateLimit();

    if (!success) {
      return tooManyRequestsResponse();
    }

    const { searchParams } = new URL(request.url);

    // Parse query parameters
    const search = normalizeTextParam(
      searchParams.get("search"),
      MAX_SEARCH_LENGTH
    );
    const genre = normalizeTextParam(searchParams.get("genre"), MAX_GENRE_LENGTH);
    const availability = normalizeTextParam(
      searchParams.get("availability"),
      20
    ).toLowerCase();
    const rating = normalizeTextParam(searchParams.get("rating"), 2);
    const sort = normalizeTextParam(searchParams.get("sort"), 20) || "title";
    const page = parseInt(searchParams.get("page") || "1", 10);
    const limitParam = parseInt(searchParams.get("limit") || "12", 10);

    if (!SORT_OPTIONS.has(sort)) {
      return badRequestResponse("Invalid sort option");
    }

    if (!AVAILABILITY_OPTIONS.has(availability)) {
      return badRequestResponse("Invalid availability filter");
    }

    if (rating) {
      const minRating = Number(rating);
      if (!Number.isInteger(minRating) || minRating < 1 || minRating > 5) {
        return badRequestResponse("Rating must be an integer from 1 to 5");
      }
    }

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
    logError("books.list_failed", error);
    return internalServerErrorResponse();
  }
}
