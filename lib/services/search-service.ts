/**
 * Advanced Search Service
 * 
 * Provides enhanced search capabilities for the book catalog.
 * Implements weighted matching across multiple fields to simulate semantic search.
 * 
 * Weighing Logic:
 * - Title Match: Highest weight (10x)
 * - Author Match: High weight (5x)
 * - Genre Match: Medium weight (3x)
 * - Summary/Description: Low weight (1x)
 */

import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import { sql, or, like, and, eq, desc, asc, getTableColumns } from "drizzle-orm";

export interface SearchOptions {
  query: string;
  genre?: string;
  limit?: number;
  offset?: number;
  sortBy?: "relevance" | "title" | "rating" | "date";
}

/**
 * Perform a weighted advanced search on books.
 * 
 * This function calculates a "relevance score" for each book based on the search query
 * and sorts the results by this score.
 */
export async function performAdvancedSearch(options: SearchOptions) {
  const { query, genre, limit = 12, offset = 0, sortBy = "relevance" } = options;
  const searchPattern = `%${query}%`;

  // SQL expression for relevance scoring
  const relevanceScore = sql<number>`
    (CASE WHEN ${books.title} LIKE ${searchPattern} THEN 10 ELSE 0 END) +
    (CASE WHEN ${books.author} LIKE ${searchPattern} THEN 5 ELSE 0 END) +
    (CASE WHEN ${books.genre} LIKE ${searchPattern} THEN 3 ELSE 0 END) +
    (CASE WHEN ${books.summary} LIKE ${searchPattern} THEN 1 ELSE 0 END)
  `;

  const whereConditions = [
    eq(books.isActive, true),
    genre ? eq(books.genre, genre) : sql`1=1`,
    or(
      like(books.title, searchPattern),
      like(books.author, searchPattern),
      like(books.genre, searchPattern),
      like(books.summary, searchPattern)
    )
  ];

  const whereClause = and(...whereConditions);

  const baseQuery = db
    .select({
      ...getTableColumns(books),
      relevance: relevanceScore,
    })
    .from(books)
    .where(whereClause);

  // Apply sorting
  let queryWithOrder;
  if (sortBy === "relevance") {
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

  const [results, totalCountResult] = await Promise.all([
    queryWithOrder.limit(limit).offset(offset),
    db.select({ count: sql<number>`count(*)` }).from(books).where(whereClause)
  ]);
  
  return {
    books: results,
    total: totalCountResult[0]?.count || 0
  };
}
