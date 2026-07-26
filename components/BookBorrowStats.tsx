/**
 * BookBorrowStats Component
 * 
 * Displays analytical statistics for a specific book, including total borrows,
 * active loans, and current availability status.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React from "react";
import { useBookBorrowStats, useBook } from "@/hooks/useQueries";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * Props for BookBorrowStats
 */
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

/**
 * BookBorrowStats
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
const BookBorrowStats: React.FC<BookBorrowStatsProps> = ({
  bookId,
  availableCopies: propAvailableCopies,
  initialBook,
  initialStats,
}) => {
  // Use React Query hook to get book data (for availableCopies that updates immediately)
  const { data: book, isLoading: bookLoading } = useBook(bookId, initialBook);

  // Use React Query hook with SSR initial data for borrow stats
  const {
    data: stats,
    isLoading: statsLoading,
    isError,
  } = useBookBorrowStats(bookId, initialStats);

  /**
   * Data Selection Logic:
   * CRITICAL: Always prefer React Query data over initial/prop data.
   * React Query data is fresh and updates immediately after mutations.
   * initial/prop data is only used as fallback during initial load.
   */
  const statsData = stats ?? initialStats;

  /**
   * Availability Calculation:
   * Get availableCopies from React Query book data (updates immediately).
   * Fallback to prop or initialBook if React Query data not yet loaded.
   */
  const availableCopies =
    book?.availableCopies ??
    initialBook?.availableCopies ??
    propAvailableCopies ??
    0;

  const isLoading = bookLoading || statsLoading;

  // Show skeleton while loading (only if no initial data is available to display)
  if (isLoading && !initialStats) {
    return (
      <div className="border-y border-[var(--mundia-line)] py-4">
        <p className="mb-3 text-sm text-slate-600">Borrowing activity</p>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-4">
          <Skeleton className="h-16 w-full rounded-lg bg-slate-200" />
          <Skeleton className="h-16 w-full rounded-lg bg-slate-200" />
          <Skeleton className="h-16 w-full rounded-lg bg-slate-200" />
          <Skeleton className="h-16 w-full rounded-lg bg-slate-200" />
        </div>
      </div>
    );
  }

  // Show error state (fallback to initial stats if available to prevent complete UI failure)
  if (isError && !initialStats) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <p className="text-sm font-medium text-red-700">
          Failed to load borrow statistics
        </p>
      </div>
    );
  }

  // Final check - if we have no data at all, don't render anything
  if (!statsData) {
    return null;
  }

  return (
    <div className="border-y border-[var(--mundia-line)] py-4">
      <p className="mb-3 text-sm text-[var(--mundia-muted)]">
        Borrowing activity
      </p>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
        {/* Total Borrows across all time */}
        <div>
          <dt className="text-sm text-[var(--mundia-muted)]">Total borrows</dt>
          <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
            {statsData.totalBorrows || 0}
          </dd>
        </div>

        {/* Current active loans */}
        <div>
          <dt className="text-sm text-[var(--mundia-muted)]">Active borrows</dt>
          <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
            {statsData.activeBorrows || 0}
          </dd>
        </div>

        {/* Real-time availability indicator */}
        <div>
          <dt className="text-sm text-[var(--mundia-muted)]">Availability</dt>
          <dd
            className={`mt-1 text-lg font-semibold ${
              availableCopies > 0
                ? "text-[var(--mundia-success-strong)]"
                : "text-[var(--mundia-danger)]"
            }`}
          >
            {availableCopies > 0 ? "Available" : "Unavailable"}
          </dd>
        </div>

        {/* Total successfully returned books */}
        <div>
          <dt className="text-sm text-[var(--mundia-muted)]">Returned</dt>
          <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
            {statsData.returnedBorrows || 0}
          </dd>
        </div>
      </dl>
    </div>
  );
};

export default BookBorrowStats;
