/**
 * Administrative Dashboard Data Aggregation
 * 
 * This file contains the "hub" for dashboard metrics. It aggregates data from 
 * across the system (users, books, borrows) into a single, highly-performant 
 * cached object.
 */

import { db } from "@/database/drizzle";
import { books, borrowRecords, users } from "@/database/schema";
import { desc, eq, gte, sql } from "drizzle-orm";
import { unstable_cache } from "next/cache";

/**
 * Utility to safely normalize numeric database aggregates into finite JavaScript numbers.
 * Prevents issues with nulls or non-numeric results from complex SQL fragments.
 * 
 * @param value - The value to normalize.
 * @returns A finite number (defaults to 0).
 */
const toNumber = (value: unknown): number => {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
};

/**
 * Core dashboard data fetcher with built-in Next.js caching.
 * 
 * Performance Strategy:
 * - Uses `unstable_cache` to store the aggregated result for 10 seconds.
 * - Tagged with `admin-stats`, `books`, `borrow-records`, and `users` to allow
 *   targeted revalidation when data changes in any of these domains.
 * - Executes all sub-queries in parallel using `Promise.all` for minimal latency.
 */
const getCachedAdminDashboardStats = unstable_cache(
  async () => {
    const fourteenDaysAgo = new Date();
    fourteenDaysAgo.setDate(fourteenDaysAgo.getDate() - 13);

    const [
      userStatsResult,
      bookStatsResult,
      borrowStatsResult,
      recentBorrowsResult,
      recentUsersResult,
      categoryStatsResult,
      booksByYearResult,
      booksByLanguageResult,
      topRatedBooksResult,
      borrowTrendsResult,
    ] = await Promise.all([
      // 1. User Account Statistics
      db
        .select({
          totalUsers: sql<number>`count(*)`,
          approvedUsers:
            sql<number>`coalesce(sum(case when ${users.status} = 'APPROVED' then 1 else 0 end), 0)`,
          pendingUsers:
            sql<number>`coalesce(sum(case when ${users.status} = 'PENDING' then 1 else 0 end), 0)`,
          adminUsers:
            sql<number>`coalesce(sum(case when ${users.role} = 'ADMIN' then 1 else 0 end), 0)`,
        })
        .from(users),
      // 2. Catalog Inventory Statistics
      db
        .select({
          totalBooks: sql<number>`count(*)`,
          totalCopies: sql<number>`coalesce(sum(${books.totalCopies}), 0)`,
          availableCopies:
            sql<number>`coalesce(sum(${books.availableCopies}), 0)`,
          activeBooks:
            sql<number>`coalesce(sum(case when ${books.isActive} = true then 1 else 0 end), 0)`,
          inactiveBooks:
            sql<number>`coalesce(sum(case when ${books.isActive} = false then 1 else 0 end), 0)`,
          booksWithISBN:
            sql<number>`coalesce(sum(case when ${books.isbn} is not null and ${books.isbn} <> '' then 1 else 0 end), 0)`,
          booksWithPublisher:
            sql<number>`coalesce(sum(case when ${books.publisher} is not null and ${books.publisher} <> '' then 1 else 0 end), 0)`,
          averagePageCount:
            sql<number>`coalesce(avg(${books.pageCount}), 0)`,
        })
        .from(books),
      // 3. Current Borrow State
      db
        .select({
          activeBorrows:
            sql<number>`coalesce(sum(case when ${borrowRecords.status} = 'BORROWED' then 1 else 0 end), 0)`,
          pendingBorrows:
            sql<number>`coalesce(sum(case when ${borrowRecords.status} = 'PENDING' then 1 else 0 end), 0)`,
          returnedBooks:
            sql<number>`coalesce(sum(case when ${borrowRecords.status} = 'RETURNED' then 1 else 0 end), 0)`,
        })
        .from(borrowRecords),
      // 4. Activity Feeds
      db
        .select({
          id: borrowRecords.id,
          bookTitle: books.title,
          userName: users.fullName,
          status: borrowRecords.status,
        })
        .from(borrowRecords)
        .innerJoin(users, eq(borrowRecords.userId, users.id))
        .innerJoin(books, eq(borrowRecords.bookId, books.id))
        .orderBy(desc(borrowRecords.createdAt))
        .limit(5),
      db
        .select({
          id: users.id,
          fullName: users.fullName,
          email: users.email,
          status: users.status,
        })
        .from(users)
        .orderBy(desc(users.createdAt))
        .limit(5),
      // 5. Categorical Breakdown (Genres)
      db
        .select({
          genre: books.genre,
          count: sql<number>`count(*)`,
          totalCopies: sql<number>`coalesce(sum(${books.totalCopies}), 0)`,
          availableCopies:
            sql<number>`coalesce(sum(${books.availableCopies}), 0)`,
          avgRating: sql<number>`coalesce(avg(${books.rating}), 0)`,
        })
        .from(books)
        .where(sql`${books.genre} is not null and ${books.genre} <> ''`)
        .groupBy(books.genre)
        .orderBy(sql`count(*) desc`)
        .limit(8),
      // 6. Chronological Distribution (Publication Years)
      db
        .select({
          year: books.publicationYear,
          count: sql<number>`count(*)`,
        })
        .from(books)
        .where(sql`${books.publicationYear} is not null`)
        .groupBy(books.publicationYear)
        .orderBy(desc(books.publicationYear))
        .limit(10),
      // 7. Linguistic Breakdown
      db
        .select({
          language: books.language,
          count: sql<number>`count(*)`,
        })
        .from(books)
        .where(sql`${books.language} is not null and ${books.language} <> ''`)
        .groupBy(books.language)
        .orderBy(sql`count(*) desc`)
        .limit(6),
      // 8. Quality Metrics (Top Rated)
      db
        .select({
          id: books.id,
          title: books.title,
          author: books.author,
          rating: books.rating,
        })
        .from(books)
        .where(eq(books.isActive, true))
        .orderBy(desc(books.rating), desc(books.createdAt))
        .limit(6),
      // 9. Operational Trends (Last 14 days)
      db
        .select({
          date: sql<string>`DATE(${borrowRecords.createdAt})`,
          borrows: sql<number>`count(*)`,
          returns:
            sql<number>`coalesce(sum(case when ${borrowRecords.status} = 'RETURNED' then 1 else 0 end), 0)`,
        })
        .from(borrowRecords)
        .where(gte(borrowRecords.createdAt, fourteenDaysAgo))
        .groupBy(sql`DATE(${borrowRecords.createdAt})`)
        .orderBy(sql`DATE(${borrowRecords.createdAt}) asc`),
    ]);

    const userStats = userStatsResult[0];
    const bookStats = bookStatsResult[0];
    const borrowStats = borrowStatsResult[0];

    const totalCopies = toNumber(bookStats?.totalCopies);
    const availableCopies = toNumber(bookStats?.availableCopies);

    return {
      totalUsers: toNumber(userStats?.totalUsers),
      approvedUsers: toNumber(userStats?.approvedUsers),
      pendingUsers: toNumber(userStats?.pendingUsers),
      adminUsers: toNumber(userStats?.adminUsers),
      totalBooks: toNumber(bookStats?.totalBooks),
      totalCopies,
      availableCopies,
      borrowedCopies: Math.max(totalCopies - availableCopies, 0),
      activeBooks: toNumber(bookStats?.activeBooks),
      inactiveBooks: toNumber(bookStats?.inactiveBooks),
      booksWithISBN: toNumber(bookStats?.booksWithISBN),
      booksWithPublisher: toNumber(bookStats?.booksWithPublisher),
      averagePageCount: Math.round(toNumber(bookStats?.averagePageCount)),
      activeBorrows: toNumber(borrowStats?.activeBorrows),
      pendingBorrows: toNumber(borrowStats?.pendingBorrows),
      returnedBooks: toNumber(borrowStats?.returnedBooks),
      recentBorrows: recentBorrowsResult.map((item) => ({
        ...item,
        status: item.status || "PENDING",
      })),
      recentUsers: recentUsersResult.map((user) => ({
        ...user,
        status: user.status || "PENDING",
      })),
      categoryStats: categoryStatsResult.map((item) => ({
        genre: item.genre || "Uncategorized",
        count: toNumber(item.count),
        totalCopies: toNumber(item.totalCopies),
        availableCopies: toNumber(item.availableCopies),
        avgRating: Number(toNumber(item.avgRating).toFixed(1)),
      })),
      booksByYear: booksByYearResult
        .filter((item) => item.year !== null)
        .map((item) => [String(item.year), toNumber(item.count)] as [
          string,
          number,
        ])
        .reverse(),
      booksByLanguage: booksByLanguageResult.map((item) => [
        item.language || "Unknown",
        toNumber(item.count),
      ]) as Array<[string, number]>,
      topRatedBooks: topRatedBooksResult.map((item) => ({
        ...item,
        rating: toNumber(item.rating),
      })),
      borrowTrends: borrowTrendsResult.map((item) => ({
        date: item.date,
        borrows: toNumber(item.borrows),
        returns: toNumber(item.returns),
      })),
    };
  },
  ["admin-dashboard-stats-v1"],
  {
    revalidate: 10,
    tags: ["admin-stats", "books", "borrow-records", "users"],
  }
);

/**
 * Public accessor for administrative dashboard statistics.
 * 
 * Flow:
 * 1. Invokes the cached fetcher.
 * 2. Handles any potential database or runtime errors gracefully.
 * 3. Returns a structured response suitable for consumption by Client Components.
 * 
 * @returns Object with success status and the aggregated dashboard data.
 */
export const getAdminDashboardStats = async () => {
  try {
    const data = await getCachedAdminDashboardStats();

    return {
      success: true as const,
      data,
    };
  } catch (error) {
    console.error("Error fetching admin dashboard stats:", error);
    return {
      success: false as const,
      error: "Failed to fetch admin dashboard stats",
    };
  }
};
