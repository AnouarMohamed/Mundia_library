/**
 * BookOverview Component
 *
 * Server Component that fetches initial book data and user data server-side.
 * Passes data to BookOverviewContent Client Component for React Query integration.
 *
 * This ensures:
 * - Fast initial load (SSR)
 * - Immediate updates when book data changes (via React Query in Client Component)
 */

import React from "react";
import { db } from "@/database/drizzle";
import { users, borrowRecords } from "@/database/schema";
import { eq, count, sql } from "drizzle-orm";
import { unstable_cache } from "next/cache";
import BookOverviewContent from "@/components/BookOverviewContent";
import type { BorrowRecord } from "@/lib/services/borrows";
import type { ReviewEligibility } from "@/lib/services/reviews";

interface Props extends Book {
  userId: string;
  isDetailPage?: boolean;
  /**
   * Initial user borrows from SSR (prevents duplicate fetch, ensures correct button state on first load)
   */
  initialUserBorrows?: BorrowRecord[];
  /**
   * Initial review eligibility from SSR (prevents duplicate fetch, ensures correct button state on first load)
   */
  initialReviewEligibility?: ReviewEligibility;
}

const getCachedUserStatus = unstable_cache(
  async (userId: string) => {
    const [user] = await db
      .select({ status: users.status })
      .from(users)
      .where(eq(users.id, userId))
      .limit(1);

    return user?.status ?? null;
  },
  ["book-overview-user-status-v1"],
  {
    revalidate: 60,
    tags: ["users"],
  }
);

const getCachedBorrowStats = unstable_cache(
  async (bookId: string) => {
    const borrowStatsResult = await db
      .select({
        totalBorrows: count(),
        activeBorrows: sql<number>`count(case when ${borrowRecords.status} = 'BORROWED' then 1 end)`,
        returnedBorrows: sql<number>`count(case when ${borrowRecords.status} = 'RETURNED' then 1 end)`,
      })
      .from(borrowRecords)
      .where(eq(borrowRecords.bookId, bookId));

    const stats = borrowStatsResult[0] || {
      totalBorrows: 0,
      activeBorrows: 0,
      returnedBorrows: 0,
    };

    return {
      totalBorrows: Number(stats.totalBorrows) || 0,
      activeBorrows: Number(stats.activeBorrows) || 0,
      returnedBorrows: Number(stats.returnedBorrows) || 0,
    };
  },
  ["book-overview-borrow-stats-v1"],
  {
    revalidate: 30,
    tags: ["borrow-records", "books"],
  }
);

const BookOverview = async ({
  id,
  userId,
  isDetailPage = false,
  initialUserBorrows,
  initialReviewEligibility,
  ...bookProps
}: Props) => {
  const [userStatus, initialStats] = await Promise.all([
    getCachedUserStatus(userId),
    getCachedBorrowStats(id),
  ]);

  // Pass all book data as initialBook to Client Component
  // The Client Component will use React Query to fetch fresh data and update immediately
  return (
    <BookOverviewContent
      bookId={id}
      userId={userId}
      userStatus={userStatus}
      isDetailPage={isDetailPage}
      initialBook={{
        ...bookProps,
        id,
      } as Book}
      initialStats={initialStats}
      initialUserBorrows={initialUserBorrows}
      initialReviewEligibility={initialReviewEligibility}
    />
  );
};

export default BookOverview;
