"use client";

/**
 * BookBorrowStats Component
 *
 * Client component that displays borrow statistics for a specific book.
 * Uses React Query for data fetching and caching, with SSR initial data support.
 *
 * Features:
 * - Uses useBookBorrowStats and useBook hooks with initialData from SSR
 * - Displays statistics: total borrows, active borrows, returned borrows
 * - Updates immediately when borrows change (via cache invalidation)
 * - Shows availability status based on availableCopies from React Query book data
 */

import React from "react";
import { useBookBorrowStats, useBook } from "@/hooks/useQueries";
import { Skeleton } from "@/components/ui/skeleton";

interface BookBorrowStatsProps {
  /**
   * Book ID (UUID)
   */
  bookId: string;
  /**
   * Initial available copies (from SSR, fallback only - React Query data takes precedence)
   * @deprecated Use initialBook prop instead for better data consistency
   */
  availableCopies?: number;
  /**
   * Initial book data from SSR (prevents duplicate fetch, provides availableCopies)
   */
  initialBook?: Book;
  /**
   * Initial borrow statistics from SSR (prevents duplicate fetch)
   */
  initialStats?: {
    totalBorrows: number;
    activeBorrows: number;
    returnedBorrows: number;
  };
}

const BookBorrowStats: React.FC<BookBorrowStatsProps> = ({
  bookId,
  availableCopies: propAvailableCopies,
  initialBook,
  initialStats,
}) => {
  // Use React Query hook to get book data (for availableCopies that updates immediately)
  const {
    data: book,
    isLoading: bookLoading,
  } = useBook(bookId, initialBook);

  // Use React Query hook with SSR initial data for borrow stats
  const {
    data: stats,
    isLoading: statsLoading,
    isError,
  } = useBookBorrowStats(bookId, initialStats);

  // CRITICAL: Always prefer React Query data over initial/prop data
  // React Query data is fresh and updates immediately after mutations
  // initial/prop data is only used as fallback during initial load
  const statsData = stats ?? initialStats;
  
  // Get availableCopies from React Query book data (updates immediately)
  // Fallback to prop or initialBook if React Query data not yet loaded
  const availableCopies = book?.availableCopies ?? 
    initialBook?.availableCopies ?? 
    propAvailableCopies ?? 
    0;

  const isLoading = bookLoading || statsLoading;

  // Show skeleton while loading (only if no initial data)
  if (isLoading && !initialStats) {
    return (
      <div className="rounded-2xl border border-white/10 bg-white/5 p-4 sm:p-5">
        <div className="mb-3 text-xs font-semibold uppercase tracking-[0.22em] text-light-200/75 sm:text-sm">
          Borrow Insights
        </div>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <Skeleton className="h-16 w-full rounded-xl bg-white/10" />
          <Skeleton className="h-16 w-full rounded-xl bg-white/10" />
          <Skeleton className="h-16 w-full rounded-xl bg-white/10" />
          <Skeleton className="h-16 w-full rounded-xl bg-white/10" />
        </div>
      </div>
    );
  }

  // Show error state (fallback to initial stats if available)
  if (isError && !initialStats) {
    return (
      <div className="rounded-2xl border border-red-300/30 bg-red-500/10 p-4">
        <p className="text-xs font-semibold uppercase tracking-[0.16em] text-red-200 sm:text-sm">
          Failed to load borrow statistics
        </p>
      </div>
    );
  }

  if (!statsData) {
    return null;
  }

  return (
    <div className="rounded-2xl border border-white/10 bg-white/5 p-4 sm:p-5">
      <div className="mb-3 text-xs font-semibold uppercase tracking-[0.22em] text-light-200/75 sm:text-sm">
        Borrow Insights
      </div>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <div className="rounded-xl border border-white/10 bg-white/[0.03] px-4 py-3">
          <p className="text-[11px] uppercase tracking-[0.16em] text-light-100/60">
            Total Borrows
          </p>
          <p className="mt-1 text-lg font-semibold text-light-100">
            {statsData.totalBorrows || 0}
          </p>
        </div>

        <div className="rounded-xl border border-white/10 bg-white/[0.03] px-4 py-3">
          <p className="text-[11px] uppercase tracking-[0.16em] text-light-100/60">
            Active Borrows
          </p>
          <p className="mt-1 text-lg font-semibold text-light-100">
            {statsData.activeBorrows || 0}
          </p>
        </div>

        <div className="rounded-xl border border-white/10 bg-white/[0.03] px-4 py-3">
          <p className="text-[11px] uppercase tracking-[0.16em] text-light-100/60">
            Availability
          </p>
          <p
            className={`mt-1 text-lg font-semibold ${
              availableCopies > 0 ? "text-green-300" : "text-red-300"
            }`}
          >
            {availableCopies > 0 ? "Available" : "Unavailable"}
          </p>
        </div>

        <div className="rounded-xl border border-white/10 bg-white/[0.03] px-4 py-3">
          <p className="text-[11px] uppercase tracking-[0.16em] text-light-100/60">
            Returned
          </p>
          <p className="mt-1 text-lg font-semibold text-light-100">
            {statsData.returnedBorrows || 0}
          </p>
        </div>
      </div>
    </div>
  );
};

export default BookBorrowStats;
