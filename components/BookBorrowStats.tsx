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

/**
 * Borrow stats panel with availability state.
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

  // CRITICAL: Always prefer React Query data over initial/prop data
  // React Query data is fresh and updates immediately after mutations
  // initial/prop data is only used as fallback during initial load
  const statsData = stats ?? initialStats;

  // Get availableCopies from React Query book data (updates immediately)
  // Fallback to prop or initialBook if React Query data not yet loaded
  const availableCopies =
    book?.availableCopies ??
    initialBook?.availableCopies ??
    propAvailableCopies ??
    0;

  const isLoading = bookLoading || statsLoading;

  // Show skeleton while loading (only if no initial data)
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

  // Show error state (fallback to initial stats if available)
  if (isError && !initialStats) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <p className="text-sm font-medium text-red-700">
          Failed to load borrow statistics
        </p>
      </div>
    );
  }

  if (!statsData) {
    return null;
  }

  return (
    <div className="border-y border-[var(--mundia-line)] py-4">
      <p className="mb-3 text-sm text-[var(--mundia-muted)]">
        Borrowing activity
      </p>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
        <div>
          <dt className="text-sm text-[var(--mundia-muted)]">Total borrows</dt>
          <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
            {statsData.totalBorrows || 0}
          </dd>
        </div>

        <div>
          <dt className="text-sm text-[var(--mundia-muted)]">Active borrows</dt>
          <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
            {statsData.activeBorrows || 0}
          </dd>
        </div>

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
