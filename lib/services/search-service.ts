/**
 * Advanced Search Service
 * 
 * This module provides high-performance search capabilities for the book catalog.
 * It implements a weighted relevance scoring system to ensure that the most
 * pertinent books appear at the top of the search results.
 * 
 * Weighting Strategy:
 * - Title Matches: 10 points (Highest relevance)
 * - Author Matches: 5 points
 * - Genre Matches: 3 points
 * - Summary/Description Matches: 1 point (Contextual relevance)
 * 
 * This service allows for complex filtering by genre and supports multiple 
 * sorting modes (relevance, title, rating, or date).
 */

import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import { sql, or, ilike, and, eq, desc, asc, getTableColumns } from "drizzle-orm";

const MIN_SEARCH_LENGTH = 2;
const MAX_SEARCH_LENGTH = 100;
const MAX_SEARCH_LIMIT = 50;

/**
 * Configuration options for executing a catalog search.
 */
export interface SearchOptions {
  /** The raw text query provided by the user. */
  query: string;
  /** Optional filter to restrict results to a specific genre. */
  genre?: string;
  /** Maximum number of results to return per page. */
  limit?: number;
  /** Number of results to skip (for pagination). */
  offset?: number;
  /** Criteria for ordering the results. */
  sortBy?: "relevance" | "title" | "rating" | "date";
}

/**
 * Performs a weighted advanced search against the books table.
 * 
 * It dynamically constructs a SQL query that calculates a `relevance` score
 * for every matching record based on where the search term was found.
 * 
 * @param options - Search configuration (query, filters, pagination, sorting).
 * @returns A promise resolving to an object containing the list of books and total match count.
 */
export async function performAdvancedSearch(options: SearchOptions) {
  const { query, genre, limit = 12, offset = 0, sortBy = "relevance" } = options;
  const normalizedQuery = query.trim().slice(0, MAX_SEARCH_LENGTH);
  const safeLimit = Math.min(MAX_SEARCH_LIMIT, Math.max(1, limit));
  const safeOffset = Math.max(0, offset);

  if (normalizedQuery.length < MIN_SEARCH_LENGTH) {
    return {
      books: [],
      total: 0,
    };
  }

  const searchPattern = `%${normalizedQuery}%`;

  // SQL expression for relevance scoring.
  // This CASE statement assigns weights to matches in different columns.
  const relevanceScore = sql<number>`
    (CASE WHEN ${books.title} ILIKE ${searchPattern} THEN 10 ELSE 0 END) +
    (CASE WHEN ${books.author} ILIKE ${searchPattern} THEN 5 ELSE 0 END) +
    (CASE WHEN ${books.genre} ILIKE ${searchPattern} THEN 3 ELSE 0 END) +
    (CASE WHEN ${books.summary} ILIKE ${searchPattern} THEN 1 ELSE 0 END)
  `;

  // Construct the base WHERE conditions
  const whereConditions = [
    eq(books.isActive, true), // Only search for available/active books
    genre ? eq(books.genre, genre) : sql`1=1`, // Genre filter (or no-op if null)
    or(
      ilike(books.title, searchPattern),
      ilike(books.author, searchPattern),
      ilike(books.genre, searchPattern),
      ilike(books.summary, searchPattern)
    )
  ];

  const whereClause = and(...whereConditions);

  // Define the selection with the computed relevance score
  const baseQuery = db
    .select({
      ...getTableColumns(books),
      relevance: relevanceScore,
    })
    .from(books)
    .where(whereClause);

  // Apply sorting logic based on the requested strategy
  let queryWithOrder;
  if (sortBy === "relevance") {
    // Sort primarily by relevance score, with title as a tie-breaker
    queryWithOrder = baseQuery.orderBy(desc(relevanceScore), asc(books.title));
  } else if (sortBy === "title") {
    queryWithOrder = baseQuery.orderBy(asc(books.title));
  } else if (sortBy === "rating") {
    queryWithOrder = baseQuery.orderBy(desc(books.rating));
  } else if (sortBy === "date") {
    queryWithOrder = baseQuery.orderBy(desc(books.createdAt));
  } else {
    queryWithOrder = baseQuery;
  }

  // Execute both the search and a total count query in parallel for efficiency
  const [results, totalCountResult] = await Promise.all([
    queryWithOrder.limit(safeLimit).offset(safeOffset),
    db.select({ count: sql<number>`count(*)` }).from(books).where(whereClause)
  ]);
  
  return {
    books: results,
    total: totalCountResult[0]?.count || 0
  };
}
