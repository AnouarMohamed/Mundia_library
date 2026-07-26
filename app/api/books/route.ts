/**
 * Books API Endpoint
 * 
 * Provides a searchable, filterable, and paginated list of books.
 * Utilizes a multi-layered caching strategy:
 * 1. Redis (Upstash) for fast global distributed caching.
 * 2. Next.js unstable_cache for per-node memory caching and revalidation.
 * 
 * @module app/api/books/route
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
 * Force Node.js runtime for database connectivity and Redis access.
 */
export const runtime = "nodejs";

// Input validation constants
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
  books: Array<typeof books.$inferSelect & { relevance?: number }>;
  pagination: {
    currentPage: number;
    totalPages: number;
    totalBooks: number;
    booksPerPage: number;
  };
}

/**
 * Internal function to fetch a page of books, handling both database queries and Redis caching.
 * 
 * @param {BooksQueryInput} input - Validated query parameters
 * @returns {Promise<BooksResult>} Object containing book list and pagination metadata
 */
const fetchBooksPage = async (input: BooksQueryInput): Promise<BooksResult> => {
  const cacheKey = `books:list:${JSON.stringify(input)}`;

  // 1. Try to retrieve data from the primary Redis cache (Upstash).
  const { data: cachedResult, isStale } =
    await getCachedData<BooksResult>(cacheKey);

  if (cachedResult && !isStale) {
    return cachedResult;
  }

  /**
   * Encapsulated database fetch logic.
   */
  const fetchFromDb = async (): Promise<BooksResult> => {
    const offset = (input.page - 1) * input.limit;

    let result: BooksResult;
    
    // Check if we are performing a text search or a filtered list query.
    if (input.search) {
      // Use the advanced search service (handles full-text search and relevance).
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
      // Standard filtered list query using Drizzle ORM.
      const whereConditions = [eq(books.isActive, true)];

      if (input.genre) {
        whereConditions.push(eq(books.genre, input.genre));
      }

      // Handle availability filtering via SQL fragments.
      if (input.availability === "available") {
        whereConditions.push(sql`${books.availableCopies} > 0`);
      } else if (input.availability === "unavailable") {
        whereConditions.push(sql`${books.availableCopies} = 0`);
      }

      if (input.rating) {
        const minRating = parseInt(input.rating, 10);
        whereConditions.push(sql`${books.rating} >= ${minRating}`);
      }

      // Define sort order.
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

      // Execute both main data query and count query in parallel.
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

    // Populate the Redis cache (5m fresh, 1h stale-while-revalidate).
    await setCachedData(cacheKey, result, { ttl: 300, swr: 3600 });
    return result;
  };

  // Stale-While-Revalidate (SWR) implementation for Redis.
  if (cachedResult && isStale) {
    // Return stale data immediately and refresh cache in the background.
    fetchFromDb().catch((err) => logError("books.swr_refresh_failed", err));
    return cachedResult;
  }

  return await fetchFromDb();
};

/**
 * Secondary cache layer using Next.js unstable_cache.
 * This caches the result in memory on the specific server node.
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
 * GET Handler for /api/books
 * 
 * Expected Query Parameters:
 * - search (string): Optional search term
 * - genre (string): Optional genre filter
 * - availability (available|unavailable): Optional availability filter
 * - rating (1-5): Optional minimum rating filter
 * - sort (title|author|rating|date): Optional sort field
 * - page (number): Page index (starts at 1)
 * - limit (number): Results per page (max 50)
 * 
 * @param {NextRequest} request - Next.js Request object
 * @returns {NextResponse} JSON response containing books and pagination info
 */
export async function GET(request: NextRequest) {
  try {
    // 1. Rate Limiting: protect against automated scraping and abuse.
    const success = await enforceRateLimit();
    if (!success) {
      return tooManyRequestsResponse();
    }

    const { searchParams } = new URL(request.url);

    // 2. Input Normalization and Validation.
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

    // Parameter Validation
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

    // 3. Data Retrieval via Caching Layers.
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
