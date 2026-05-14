import { db } from "@/database/drizzle";
import { books, users, borrowRecords } from "@/database/schema";
import { eq, sql, desc, and, gte, lt, count } from "drizzle-orm";

// Get borrowing trends over time (last 30 days)
/**
 * Fetch borrowing trends over the last 30 days.
 */
export async function getBorrowingTrends() {
  const thirtyDaysAgo = new Date();
  thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

  const trends = await db
    .select({
      date: sql<string>`DATE(${borrowRecords.createdAt})`,
      borrows: count(),
      returns: sql<number>`count(case when ${borrowRecords.status} = 'RETURNED' then 1 end)`,
    })
    .from(borrowRecords)
    .where(gte(borrowRecords.createdAt, thirtyDaysAgo))
    .groupBy(sql`DATE(${borrowRecords.createdAt})`)
    .orderBy(sql`DATE(${borrowRecords.createdAt})`);

  return trends;
}

// Get most popular books/genres
/**
 * Fetch most borrowed books.
 */
export async function getPopularBooks(limit = 10) {
  const popularBooks = await db
    .select({
      bookId: borrowRecords.bookId,
      bookTitle: books.title,
      bookAuthor: books.author,
      bookGenre: books.genre,
      totalBorrows: count(),
      activeBorrows: sql<number>`count(case when ${borrowRecords.status} = 'BORROWED' then 1 end)`,
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
 * Fetch most borrowed genres.
 */
export async function getPopularGenres() {
  const popularGenres = await db
    .select({
      genre: books.genre,
      totalBorrows: count(),
      uniqueBooks: sql<number>`count(distinct ${borrowRecords.bookId})`,
    })
    .from(borrowRecords)
    .innerJoin(books, eq(borrowRecords.bookId, books.id))
    .groupBy(books.genre)
    .orderBy(desc(count()))
    .limit(10);

  return popularGenres;
}

// Get user activity patterns
/**
 * Fetch user activity aggregates.
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
      lastActivity: sql<Date>`max(${borrowRecords.createdAt})`,
    })
    .from(borrowRecords)
    .innerJoin(users, eq(borrowRecords.userId, users.id))
    .groupBy(borrowRecords.userId, users.fullName, users.email)
    .orderBy(desc(count()))
    .limit(20);

  return userActivity;
}

// Get overdue book analysis
/**
 * Fetch overdue analysis details.
 */
export async function getOverdueAnalysis() {
  const now = new Date();

  // Get current daily fine amount from system config
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
      daysOverdue: sql<number>`CASE 
        WHEN ${borrowRecords.dueDate} IS NOT NULL 
        THEN (CAST(${now} AS date) - ${borrowRecords.dueDate})
        ELSE 0 
      END`,
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

// Get overdue statistics
/**
 * Fetch aggregate overdue statistics.
 */
export async function getOverdueStats() {
  const now = new Date();

  // Get current daily fine amount from system config
  const { getDailyFineAmount } = await import("./config");
  const dailyFineAmount = await getDailyFineAmount();

  const stats = await db
    .select({
      totalOverdue: sql<number>`count(case when ${borrowRecords.dueDate} < ${now} and ${borrowRecords.status} = 'BORROWED' then 1 end)`,
      totalFines: sql<number>`COALESCE(sum(case when ${borrowRecords.dueDate} < ${now} and ${borrowRecords.status} = 'BORROWED' then ((CAST(${now} AS date) - ${borrowRecords.dueDate}) * ${dailyFineAmount}) end), 0)`,
      avgDaysOverdue: sql<number>`COALESCE(AVG(case when ${borrowRecords.dueDate} < ${now} and ${borrowRecords.status} = 'BORROWED' then (CAST(${now} AS date) - ${borrowRecords.dueDate}) end), 0)`,
    })
    .from(borrowRecords);

  return {
    totalOverdue: stats[0]?.totalOverdue || 0,
    totalFines: stats[0]?.totalFines || 0,
    avgDaysOverdue: Number(stats[0]?.avgDaysOverdue) || 0,
  };
}

// Get monthly borrowing statistics
/**
 * Fetch current vs last month borrow stats.
 */
export async function getMonthlyStats() {
  const currentMonth = new Date();
  const lastMonth = new Date();
  lastMonth.setMonth(lastMonth.getMonth() - 1);

  // Construct month strings in JavaScript
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
          borrowRecords.createdAt,
          new Date(lastMonth.getFullYear(), lastMonth.getMonth(), 1)
        ),
        lt(
          borrowRecords.createdAt,
          new Date(lastMonth.getFullYear(), lastMonth.getMonth() + 1, 1)
        )
      )
    );

  return {
    currentMonth: currentMonthStats[0] || { month: "", borrows: 0 },
    lastMonth: lastMonthStats[0] || { month: "", borrows: 0 },
  };
}

// Get system health metrics
/**
 * Fetch system health metrics.
 */
export async function getSystemHealth() {
  const now = new Date();
  const sevenDaysAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

  // Get individual metrics
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
 * Get genre performance broken down by month.
 * 
 * Returns the top genres for each of the last 6 months.
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
