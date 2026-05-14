/**
 * Administrative Analytics Server Actions
 * 
 * This file provides data aggregation and analysis functions for the admin dashboard.
 * It uses complex SQL fragments and joins to provide high-level insights into 
 * library usage, inventory performance, and student behavior.
 */

import { db } from "@/database/drizzle";
import { books, users, borrowRecords } from "@/database/schema";
import { eq, sql, desc, and, gte, lt, count } from "drizzle-orm";

/**
 * Calculates borrowing and return trends over the last 30 days.
 * 
 * Use Case: Powering the "Borrowing Trends" line chart in the dashboard.
 * 
 * @returns Array of daily aggregates (date, borrow count, return count).
 */
export async function getBorrowingTrends() {
  const thirtyDaysAgo = new Date();
  thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

  const trends = await db
    .select({
      /** Formatted date (YYYY-MM-DD). */
      date: sql<string>`DATE(${borrowRecords.createdAt})`,
      /** Total borrow requests created on this day. */
      borrows: count(),
      /** Number of books returned on this day. */
      returns: sql<number>`count(case when ${borrowRecords.status} = 'RETURNED' then 1 end)`,
    })
    .from(borrowRecords)
    .where(gte(borrowRecords.createdAt, thirtyDaysAgo))
    .groupBy(sql`DATE(${borrowRecords.createdAt})`)
    .orderBy(sql`DATE(${borrowRecords.createdAt})`);

  return trends;
}

/**
 * Identifies the most frequently borrowed books.
 * 
 * @param limit - Maximum number of books to return (default: 10).
 * @returns Array of books with lifetime and active borrow counts.
 */
export async function getPopularBooks(limit = 10) {
  const popularBooks = await db
    .select({
      bookId: borrowRecords.bookId,
      bookTitle: books.title,
      bookAuthor: books.author,
      bookGenre: books.genre,
      /** Lifetime borrow count. */
      totalBorrows: count(),
      /** Number of copies currently out with students. */
      activeBorrows: sql<number>`count(case when ${borrowRecords.status} = 'BORROWED' then 1 end)`,
      /** Total number of successful returns. */
      returnedBorrows: sql<number>`count(case when ${borrowRecords.status} = 'RETURNED' then 1 end)`,
    })
    .from(borrowRecords)
    .innerJoin(books, eq(borrowRecords.bookId, books.id))
    .groupBy(borrowRecords.bookId, books.title, books.author, books.genre)
    .orderBy(desc(count()))
    .limit(limit);

  return popularBooks;
}

/**
 * Aggregates borrow counts by genre to identify high-interest categories.
 * 
 * @returns Top 10 genres by borrow volume.
 */
export async function getPopularGenres() {
  const popularGenres = await db
    .select({
      genre: books.genre,
      /** Total borrows across all books in this genre. */
      totalBorrows: count(),
      /** Number of distinct book titles borrowed in this genre. */
      uniqueBooks: sql<number>`count(distinct ${borrowRecords.bookId})`,
    })
    .from(borrowRecords)
    .innerJoin(books, eq(borrowRecords.bookId, books.id))
    .groupBy(books.genre)
    .orderBy(desc(count()))
    .limit(10);

  return popularGenres;
}

/**
 * Analyzes student interaction patterns with the library.
 * 
 * @returns Top 20 most active students by borrow volume.
 */
export async function getUserActivityPatterns() {
  const userActivity = await db
    .select({
      userId: borrowRecords.userId,
      userName: users.fullName,
      userEmail: users.email,
      totalBorrows: count(),
      activeBorrows: sql<number>`count(case when ${borrowRecords.status} = 'BORROWED' then 1 end)`,
      returnedBorrows: sql<number>`count(case when ${borrowRecords.status} = 'RETURNED' then 1 end)`,
      pendingBorrows: sql<number>`count(case when ${borrowRecords.status} = 'PENDING' then 1 end)`,
      /** Most recent interaction timestamp. */
      lastActivity: sql<Date>`max(${borrowRecords.createdAt})`,
    })
    .from(borrowRecords)
    .innerJoin(users, eq(borrowRecords.userId, users.id))
    .groupBy(borrowRecords.userId, users.fullName, users.email)
    .orderBy(desc(count()))
    .limit(20);

  return userActivity;
}

/**
 * Provides a granular view of all currently overdue books.
 * 
 * Logic:
 * - Joins users and books to provide contact and catalog info.
 * - Calculates `daysOverdue` and `fineAmount` on-the-fly for real-time accuracy.
 * 
 * @returns Detailed list of overdue records.
 */
export async function getOverdueAnalysis() {
  const now = new Date();

  const { getDailyFineAmount } = await import("./config");
  const dailyFineAmount = await getDailyFineAmount();

  const overdueBooks = await db
    .select({
      recordId: borrowRecords.id,
      bookTitle: books.title,
      bookAuthor: books.author,
      userName: users.fullName,
      userEmail: users.email,
      borrowDate: borrowRecords.borrowDate,
      dueDate: borrowRecords.dueDate,
      /** Difference between today and due date in days. */
      daysOverdue: sql<number>`CASE 
        WHEN ${borrowRecords.dueDate} IS NOT NULL 
        THEN (CAST(${now} AS date) - ${borrowRecords.dueDate})
        ELSE 0 
      END`,
      /** Projected fine if book was returned today. */
      fineAmount: sql<string>`CASE 
        WHEN ${borrowRecords.dueDate} IS NOT NULL AND ${borrowRecords.dueDate} < ${now}
        THEN CAST(((CAST(${now} AS date) - ${borrowRecords.dueDate}) * ${dailyFineAmount}) AS text)
        ELSE '0.00'
      END`,
    })
    .from(borrowRecords)
    .innerJoin(books, eq(borrowRecords.bookId, books.id))
    .innerJoin(users, eq(borrowRecords.userId, users.id))
    .where(
      and(
        eq(borrowRecords.status, "BORROWED"),
        sql`${borrowRecords.dueDate} < ${now}`
      )
    )
    .orderBy(sql`(CAST(${now} AS date) - ${borrowRecords.dueDate}) DESC`);

  return overdueBooks;
}

/**
 * Calculates high-level overdue KPIs for the dashboard cards.
 * 
 * @returns Aggregate counts and sums for overdue loans.
 */
export async function getOverdueStats() {
  const now = new Date();

  const { getDailyFineAmount } = await import("./config");
  const dailyFineAmount = await getDailyFineAmount();

  const stats = await db
    .select({
      /** Total count of active, overdue loans. */
      totalOverdue: sql<number>`count(case when ${borrowRecords.dueDate} < ${now} and ${borrowRecords.status} = 'BORROWED' then 1 end)`,
      /** Total projected revenue from active overdue fines. */
      totalFines: sql<number>`COALESCE(sum(case when ${borrowRecords.dueDate} < ${now} and ${borrowRecords.status} = 'BORROWED' then ((CAST(${now} AS date) - ${borrowRecords.dueDate}) * ${dailyFineAmount}) end), 0)`,
      /** Average delay (in days) across all overdue loans. */
      avgDaysOverdue: sql<number>`COALESCE(AVG(case when ${borrowRecords.dueDate} < ${now} and ${borrowRecords.status} = 'BORROWED' then (CAST(${now} AS date) - ${borrowRecords.dueDate}) end), 0)`,
    })
    .from(borrowRecords);

  return {
    totalOverdue: stats[0]?.totalOverdue || 0,
    totalFines: stats[0]?.totalFines || 0,
    avgDaysOverdue: Number(stats[0]?.avgDaysOverdue) || 0,
  };
}

/**
 * Compares borrowing volume between the current and previous month.
 * 
 * Used for "Month-over-Month" growth indicators.
 * 
 * @returns Object with current and last month stats.
 */
export async function getMonthlyStats() {
  const currentMonth = new Date();
  const lastMonth = new Date();
  lastMonth.setMonth(lastMonth.getMonth() - 1);

  const currentMonthStr = `${currentMonth.getFullYear()}-${String(currentMonth.getMonth() + 1).padStart(2, "0")}`;
  const lastMonthStr = `${lastMonth.getFullYear()}-${String(lastMonth.getMonth() + 1).padStart(2, "0")}`;

  const currentMonthStats = await db
    .select({
      month: sql<string>`${currentMonthStr}`,
      borrows: count(),
    })
    .from(borrowRecords)
    .where(
      and(
        gte(
          borrowRecords.createdAt,
          new Date(currentMonth.getFullYear(), currentMonth.getMonth(), 1)
        ),
        lt(
          borrowRecords.createdAt,
          new Date(currentMonth.getFullYear(), currentMonth.getMonth() + 1, 1)
        )
      )
    );

  const lastMonthStats = await db
    .select({
      month: sql<string>`${lastMonthStr}`,
      borrows: count(),
    })
    .from(borrowRecords)
    .where(
      and(
        gte(
          lastMonth.getFullYear(),
          lastMonth.getMonth(),
          1
        ),
        lt(
          lastMonth.getFullYear(),
          lastMonth.getMonth() + 1,
          1
        )
      )
    );

  return {
    currentMonth: currentMonthStats[0] || { month: "", borrows: 0 },
    lastMonth: lastMonthStats[0] || { month: "", borrows: 0 },
  };
}

/**
 * Provides basic system-wide counters for inventory and user base.
 * 
 * @returns Snapshot of total counts (books, users, active loans, etc.).
 */
export async function getSystemHealth() {
  const now = new Date();
  const sevenDaysAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

  const [
    totalBooksResult,
    totalUsersResult,
    activeBorrowsResult,
    pendingRequestsResult,
    overdueBooksResult,
    recentActivityResult,
  ] = await Promise.all([
    db.select({ count: count() }).from(books),
    db.select({ count: count() }).from(users),
    db
      .select({ count: count() })
      .from(borrowRecords)
      .where(eq(borrowRecords.status, "BORROWED")),
    db
      .select({ count: count() })
      .from(borrowRecords)
      .where(eq(borrowRecords.status, "PENDING")),
    db
      .select({ count: count() })
      .from(borrowRecords)
      .where(
        and(
          sql`${borrowRecords.dueDate} < ${now}`,
          eq(borrowRecords.status, "BORROWED")
        )
      ),
    db
      .select({ count: count() })
      .from(borrowRecords)
      .where(gte(borrowRecords.createdAt, sevenDaysAgo)),
  ]);

  return {
    totalBooks: totalBooksResult[0]?.count || 0,
    totalUsers: totalUsersResult[0]?.count || 0,
    activeBorrows: activeBorrowsResult[0]?.count || 0,
    pendingRequests: pendingRequestsResult[0]?.count || 0,
    overdueBooks: overdueBooksResult[0]?.count || 0,
    recentActivity: recentActivityResult[0]?.count || 0,
  };
}

/**
 * Tracks genre popularity trends over a 6-month period.
 * 
 * Use Case: Powering the "Genre Performance" multi-line chart.
 * 
 * @returns Grouped counts by month and genre.
 */
export async function getGenrePerformanceByMonth() {
  const sixMonthsAgo = new Date();
  sixMonthsAgo.setMonth(sixMonthsAgo.getMonth() - 6);

  const performance = await db
    .select({
      month: sql<string>`to_char(${borrowRecords.createdAt}, 'YYYY-MM')`,
      genre: books.genre,
      borrowCount: count(),
    })
    .from(borrowRecords)
    .innerJoin(books, eq(borrowRecords.bookId, books.id))
    .where(gte(borrowRecords.createdAt, sixMonthsAgo))
    .groupBy(sql`to_char(${borrowRecords.createdAt}, 'YYYY-MM')`, books.genre)
    .orderBy(sql`to_char(${borrowRecords.createdAt}, 'YYYY-MM') DESC`, count());

  return performance;
}
