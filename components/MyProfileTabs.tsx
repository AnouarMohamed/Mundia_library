"use client";

/**
 * MyProfileTabs Component
 *
 * Client component that displays user's borrow records in tabs (Active, Pending, History).
 * Uses React Query for data fetching and caching, with SSR initial data support.
 *
 * Features:
 * - Uses useUserBorrows hook with initialData from SSR
 * - Displays skeleton loaders while fetching
 * - Shows error state if fetch fails
 * - Filters borrows by status client-side
 * - Supports tab navigation via URL params
 */

import React from "react";
import Link from "next/link";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import BookCover from "@/components/BookCover";
import CountdownTimer from "@/components/CountdownTimer";
import BorrowSkeleton from "@/components/skeletons/BorrowSkeleton";
import {
  BookOpen,
  Clock,
  Calendar,
  AlertTriangle,
  Star,
  Eye,
  RotateCcw,
} from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useUserBorrows } from "@/hooks/useQueries";
import { useReturnBook } from "@/hooks/useMutations";
import type { BorrowRecord } from "@/lib/services/borrows";
import RenewalRequestButton from "@/components/RenewalRequestButton";

// Define the actual data structure from the database query
interface BorrowRecordWithBook {
  id: string;
  userId: string;
  bookId: string;
  borrowDate: Date;
  dueDate: Date | null; // Can be null for pending requests
  returnDate?: Date | null;
  status: "PENDING" | "BORROWED" | "RETURNED";
  borrowedBy?: string | null;
  returnedBy?: string | null;
  fineAmount: number;
  notes?: string | null;
  renewalCount: number;
  lastReminderSent?: Date | null;
  updatedAt: Date | null;
  updatedBy?: string | null;
  createdAt: Date | null;
  book: {
    id: string;
    title: string;
    author: string;
    genre: string;
    rating: number;
    totalCopies: number;
    availableCopies: number;
    description: string;
    coverColor: string;
    coverUrl: string;
    videoUrl: string;
    summary: string;
    isbn?: string | null;
    publicationYear?: number | null;
    publisher?: string | null;
    language?: string | null;
    pageCount?: number | null;
    edition?: string | null;
    isActive: boolean;
    createdAt: Date | null;
    updatedAt: Date | null;
    updatedBy?: string | null;
  };
}

interface MyProfileTabsProps {
  /**
   * User ID (required for React Query)
   */
  userId: string;
  /**
   * Initial active borrows from SSR (prevents duplicate fetch)
   */
  initialActiveBorrows?: BorrowRecordWithBook[];
  /**
   * Initial pending requests from SSR (prevents duplicate fetch)
   */
  initialPendingRequests?: BorrowRecordWithBook[];
  /**
   * Initial borrow history from SSR (prevents duplicate fetch)
   */
  initialBorrowHistory?: BorrowRecordWithBook[];
  /**
   * Total reviews count
   */
  totalReviews: number;
  /**
   * Legacy props for backward compatibility (deprecated, use initial* props instead)
   */
  activeBorrows?: BorrowRecordWithBook[];
  pendingRequests?: BorrowRecordWithBook[];
  borrowHistory?: BorrowRecordWithBook[];
}

/**
 * Tabbed profile view for borrow activity.
 */
const MyProfileTabs: React.FC<MyProfileTabsProps> = ({
  userId,
  initialActiveBorrows,
  initialPendingRequests,
  initialBorrowHistory,
  totalReviews,
  // Legacy props for backward compatibility
  activeBorrows: legacyActiveBorrows,
  pendingRequests: legacyPendingRequests,
  borrowHistory: legacyBorrowHistory,
}) => {
  const router = useRouter();
  const searchParams = useSearchParams();

  // Use React Query mutation for returning book
  const returnBookMutation = useReturnBook();
  // CRITICAL: Track which record is currently being returned to prevent multiple clicks
  const returningRecordIdRef = React.useRef<string | null>(null);

  // Use React Query to fetch all user borrows (no status filter to get all)
  // The API returns borrow records WITH book details (from /api/borrow-records)
  // React Query will invalidate and refetch when mutations happen, ensuring immediate UI updates
  // placeholderData in QueryProvider ensures we keep showing previous data during refetch
  const {
    data: reactQueryBorrows,
    isLoading,
    isError,
    error,
  } = useUserBorrows(
    userId,
    undefined, // No status filter - get all
    // Use initial borrow history as initial data (transform to BorrowRecord format for React Query)
    initialBorrowHistory || legacyBorrowHistory
      ? ((initialBorrowHistory || legacyBorrowHistory || []).map((record) => {
          // CRITICAL: Preserve the book field from SSR data
          // The SSR data includes book details, and we need to keep them for the UI
          // Even though BorrowRecord type doesn't include book, we cast it to include it
          const baseRecord = {
            id: record.id,
            userId: record.userId,
            bookId: record.bookId,
            borrowDate: record.borrowDate,
            dueDate: record.dueDate
              ? new Date(record.dueDate).toISOString().split("T")[0]
              : null,
            returnDate: record.returnDate
              ? new Date(record.returnDate).toISOString().split("T")[0]
              : null,
            status: record.status,
            borrowedBy: record.borrowedBy,
            returnedBy: record.returnedBy,
            fineAmount:
              typeof record.fineAmount === "number"
                ? record.fineAmount.toString()
                : String(record.fineAmount || "0"),
            notes: record.notes,
            renewalCount: record.renewalCount,
            lastReminderSent: record.lastReminderSent,
            updatedAt: record.updatedAt,
            updatedBy: record.updatedBy,
            createdAt: record.createdAt,
          };
          // Preserve book field if it exists (SSR data includes it)
          return {
            ...baseRecord,
            ...(record.book && { book: record.book }),
          };
        }) as (BorrowRecord & { book?: Book })[])
      : undefined,
  );

  // CRITICAL: No cache - always use React Query data directly
  // React Query will always fetch fresh data on mount (staleTime: 0, refetchOnMount: true)
  // No cache fallback - just use whatever React Query returns

  // CRITICAL: Always prefer React Query data over initial/legacy data
  // React Query data is fresh and updates immediately after mutations
  // The API returns borrow records WITH book details (the 'book' field is included)
  // initial/legacy data is only used as fallback during initial load
  // Transform React Query data to BorrowRecordWithBook[] format (API includes book details)
  // CRITICAL: Optimized to prevent flicker by maintaining stable array references
  // Store previous transformed records to reuse Date objects for reference stability
  const previousTransformedRef = React.useRef<
    Map<string, BorrowRecordWithBook>
  >(new Map());
  // Store previous array to maintain reference equality when data hasn't changed
  const previousArrayRef = React.useRef<BorrowRecordWithBook[]>([]);
  // CRITICAL: Store initial data as fallback for client-side navigation
  // When navigating client-side, initialData is not available, but we can use it from first render
  const initialDataRef = React.useRef<BorrowRecordWithBook[] | null>(null);

  // Store initial data on first render (SSR data)
  React.useEffect(() => {
    if (
      (initialBorrowHistory || legacyBorrowHistory) &&
      !initialDataRef.current
    ) {
      initialDataRef.current =
        initialBorrowHistory || legacyBorrowHistory || [];
    }
  }, [initialBorrowHistory, legacyBorrowHistory]);

  // Transform data using useMemo - this will recalculate when reactQueryBorrows changes
  // but we maintain stable array references to prevent unnecessary re-renders
  const allBorrowsFromQuery: BorrowRecordWithBook[] = React.useMemo(() => {
    // CRITICAL: Skip updates during logout to prevent flickering
    // Check if we're in browser environment before accessing document
    const isLoggingOut =
      typeof window !== "undefined" &&
      document.cookie
        .split("; ")
        .find((row) => row.startsWith("logout-in-progress="))
        ?.split("=")[1] === "true";

    if (isLoggingOut) {
      // During logout, return previous array to prevent flicker
      return previousArrayRef.current;
    }

    // CRITICAL: Handle empty data - React Query will fetch fresh data on mount
    // No cache fallback - just wait for React Query to fetch
    if (!reactQueryBorrows || reactQueryBorrows.length === 0) {
      // During logout, return previous array
      if (isLoggingOut) {
        return previousArrayRef.current;
      }
      // CRITICAL: If no React Query data, try to use cached data from previous render
      // This handles client-side navigation where cache might not be immediately available
      if (previousArrayRef.current.length > 0) {
        return previousArrayRef.current;
      }
      // CRITICAL: Fallback to initial data if available (for client-side navigation)
      // This ensures data is shown even when React Query cache is temporarily empty
      if (initialDataRef.current && initialDataRef.current.length > 0) {
        return initialDataRef.current;
      }
      // If no data at all, return empty array
      return [];
    }

    // Transform the data
    // CRITICAL: Cast to include book field - API and initialData both include it
    // Use reactQueryBorrows directly - no cache fallback
    const transformed = (
      reactQueryBorrows as (BorrowRecord & { book?: Book })[]
    ).map((record) => {
      const recordWithBook = record as BorrowRecord & { book?: Book };

      // CRITICAL: Reuse existing transformed record if it exists and data hasn't changed
      // This maintains reference equality for Date objects and prevents unnecessary re-renders
      const existingRecord = previousTransformedRef.current.get(record.id);

      // Check if record data has actually changed
      const dataChanged =
        !existingRecord ||
        existingRecord.status !== record.status ||
        existingRecord.bookId !== record.bookId ||
        (existingRecord.dueDate?.getTime() || 0) !==
          (record.dueDate ? new Date(record.dueDate).getTime() : 0) ||
        (existingRecord.returnDate?.getTime() || 0) !==
          (record.returnDate ? new Date(record.returnDate).getTime() : 0);

      // If data hasn't changed, reuse existing record (maintains reference equality)
      if (!dataChanged && existingRecord) {
        return existingRecord;
      }

      // Data changed or record is new, create new transformed record
      const getStableDate = (
        dateString: string | Date | null | undefined,
        existingDate: Date | null | undefined,
      ): Date | null => {
        if (!dateString) return null;
        const timestamp =
          typeof dateString === "string"
            ? new Date(dateString).getTime()
            : dateString.getTime();

        // If existing date has same timestamp, reuse it to maintain reference equality
        if (existingDate?.getTime() === timestamp) {
          return existingDate;
        }

        return new Date(timestamp);
      };

      const transformedRecord: BorrowRecordWithBook = {
        id: record.id,
        userId: record.userId,
        bookId: record.bookId,
        borrowDate:
          getStableDate(record.borrowDate, existingRecord?.borrowDate) ||
          new Date(),
        dueDate: getStableDate(record.dueDate, existingRecord?.dueDate),
        returnDate: getStableDate(
          record.returnDate,
          existingRecord?.returnDate,
        ),
        status: record.status,
        borrowedBy: record.borrowedBy,
        returnedBy: record.returnedBy,
        fineAmount:
          typeof record.fineAmount === "string"
            ? parseFloat(record.fineAmount)
            : record.fineAmount || 0,
        notes: record.notes,
        renewalCount: record.renewalCount || 0,
        lastReminderSent: getStableDate(
          record.lastReminderSent,
          existingRecord?.lastReminderSent,
        ),
        updatedAt: getStableDate(record.updatedAt, existingRecord?.updatedAt),
        updatedBy: record.updatedBy,
        createdAt: getStableDate(record.createdAt, existingRecord?.createdAt),
        // Book details from API response (the API includes 'book' field)
        // CRITICAL: Preserve book field from existing record if missing from cache
        // This prevents empty card info when cache is updated without book data
        book: recordWithBook.book ||
          existingRecord?.book || {
            id: record.bookId,
            title: "Unknown Book",
            author: "Unknown Author",
            genre: "",
            rating: 0,
            totalCopies: 0,
            availableCopies: 0,
            description: "",
            coverColor: "",
            coverUrl: "",
            videoUrl: "",
            summary: "",
            isActive: true,
            createdAt: null,
            updatedAt: null,
          },
      };

      // Store in map for next comparison
      previousTransformedRef.current.set(record.id, transformedRecord);

      return transformedRecord;
    });

    // Clean up map - remove records that no longer exist
    const currentIds = new Set(transformed.map((r) => r.id));
    for (const [id] of previousTransformedRef.current) {
      if (!currentIds.has(id)) {
        previousTransformedRef.current.delete(id);
      }
    }

    // CRITICAL: Compare with previous array to maintain reference equality
    // Only return new array if records actually changed
    const previousArray = previousArrayRef.current;
    if (
      previousArray.length === transformed.length &&
      previousArray.every(
        (prevRecord, index) =>
          prevRecord.id === transformed[index]?.id &&
          prevRecord.status === transformed[index]?.status &&
          prevRecord.bookId === transformed[index]?.bookId,
      )
    ) {
      // Array contents are the same, return previous array to maintain reference equality
      return previousArray;
    }

    // Array contents changed, update ref and return new array
    previousArrayRef.current = transformed;
    return transformed;
  }, [reactQueryBorrows]); // Transform whenever reactQueryBorrows changes
  // React Query's placeholderData ensures smooth transitions without flicker

  // CRITICAL: Clear returningRecordIdRef when record status changes to RETURNED
  // This ensures the button becomes enabled again after the UI updates
  React.useEffect(() => {
    if (returningRecordIdRef.current) {
      const returnedRecord = allBorrowsFromQuery.find(
        (r) => r.id === returningRecordIdRef.current && r.status === "RETURNED",
      );
      if (returnedRecord) {
        // Record has been returned, clear the ref to re-enable button
        returningRecordIdRef.current = null;
      }
    }
  }, [allBorrowsFromQuery]);

  // Use React Query data if available, otherwise fall back to initial/legacy data
  // CRITICAL: Memoize to prevent unnecessary recalculations in filtered arrays
  const allBorrows: BorrowRecordWithBook[] = React.useMemo(() => {
    return allBorrowsFromQuery.length > 0
      ? allBorrowsFromQuery
      : (initialBorrowHistory ?? legacyBorrowHistory ?? []);
  }, [allBorrowsFromQuery, initialBorrowHistory, legacyBorrowHistory]);

  // Filter borrows by status (client-side filtering)
  // CRITICAL: Memoize filtered arrays to prevent unnecessary re-renders
  const activeBorrows: BorrowRecordWithBook[] = React.useMemo(() => {
    return allBorrowsFromQuery.length > 0
      ? allBorrowsFromQuery.filter((record) => record.status === "BORROWED")
      : (legacyActiveBorrows ??
          initialActiveBorrows ??
          allBorrows.filter((record) => record.status === "BORROWED"));
  }, [
    allBorrowsFromQuery,
    legacyActiveBorrows,
    initialActiveBorrows,
    allBorrows,
  ]);

  const pendingRequests: BorrowRecordWithBook[] = React.useMemo(() => {
    if (allBorrowsFromQuery.length > 0) {
      return allBorrowsFromQuery.filter(
        (record) => record.status === "PENDING",
      );
    }
    return (
      legacyPendingRequests ??
      initialPendingRequests ??
      allBorrows.filter((record) => record.status === "PENDING")
    );
  }, [
    allBorrowsFromQuery,
    legacyPendingRequests,
    initialPendingRequests,
    allBorrows,
  ]);

  const borrowHistory: BorrowRecordWithBook[] = React.useMemo(() => {
    if (allBorrowsFromQuery.length > 0) {
      // CRITICAL: Filter for RETURNED status only (borrow history)
      return allBorrowsFromQuery.filter(
        (record) => record.status === "RETURNED",
      );
    }
    // Fallback to legacy/initial data, but also filter for RETURNED
    return (
      legacyBorrowHistory ??
      initialBorrowHistory ??
      allBorrows.filter((record) => record.status === "RETURNED")
    );
  }, [
    allBorrowsFromQuery,
    legacyBorrowHistory,
    initialBorrowHistory,
    allBorrows,
  ]);

  // CRITICAL: Use controlled tab state to prevent hydration mismatch
  // Radix UI generates random IDs on server/client, so we use controlled value
  const [activeTabValue, setActiveTabValue] = React.useState<string>(() => {
    // Get initial tab from URL or default to "active"
    if (typeof window !== "undefined") {
      const urlTab = searchParams.get("tab");
      return urlTab === "pending" || urlTab === "history" ? urlTab : "active";
    }
    return "active";
  });

  // Sync with URL params
  React.useEffect(() => {
    const urlTab = searchParams.get("tab");
    if (urlTab === "pending" || urlTab === "history") {
      setActiveTabValue(urlTab);
    } else if (!urlTab) {
      setActiveTabValue("active");
    }
  }, [searchParams, setActiveTabValue]);

  // Show skeleton while loading (only if no data at all - not during refetch)
  // CRITICAL: Use isLoading (not isFetching) to only show skeleton on initial load
  // isFetching would show skeleton during refetch, causing flicker
  // placeholderData ensures we keep showing previous data during refetch
  if (
    isLoading &&
    !reactQueryBorrows &&
    (!initialBorrowHistory || initialBorrowHistory.length === 0)
  ) {
    return (
      <div className="container mx-auto px-3 py-4 sm:px-4 sm:py-6">
        <Tabs
          value={activeTabValue}
          onValueChange={setActiveTabValue}
          className="w-full"
          suppressHydrationWarning
        >
          <TabsList className="flex w-full flex-wrap gap-2 sm:grid sm:grid-cols-3">
            <TabsTrigger
              value="active"
              className="min-w-[calc(33.333%-0.5rem)] flex-1 sm:min-w-0"
            >
              <span className="block text-center sm:inline">
                <span className="block sm:inline">Active</span>
                <span className="block sm:inline sm:before:content-['\00a0']">
                  Borrows
                </span>
              </span>
            </TabsTrigger>
            <TabsTrigger
              value="pending"
              className="min-w-[calc(33.333%-0.5rem)] flex-1 sm:min-w-0"
            >
              <span className="block text-center sm:inline">
                <span className="block sm:inline">Pending</span>
                <span className="block sm:inline sm:before:content-['\00a0']">
                  Requests
                </span>
              </span>
            </TabsTrigger>
            <TabsTrigger
              value="history"
              className="min-w-[calc(33.333%-0.5rem)] flex-1 sm:min-w-0"
            >
              <span className="block text-center sm:inline">
                <span className="block sm:inline">Borrow</span>
                <span className="block sm:inline sm:before:content-['\00a0']">
                  History
                </span>
              </span>
            </TabsTrigger>
          </TabsList>
          <TabsContent value="active" className="mt-4 sm:mt-6">
            <div className="space-y-3 sm:space-y-4">
              {[...Array(3)].map((_, i) => (
                <BorrowSkeleton key={`active-${i}`} variant="profile" />
              ))}
            </div>
          </TabsContent>
          <TabsContent value="pending" className="mt-4 sm:mt-6">
            <div className="space-y-3 sm:space-y-4">
              {[...Array(2)].map((_, i) => (
                <BorrowSkeleton key={`pending-${i}`} variant="profile" />
              ))}
            </div>
          </TabsContent>
          <TabsContent value="history" className="mt-4 sm:mt-6">
            <div className="space-y-3 sm:space-y-4">
              {[...Array(5)].map((_, i) => (
                <BorrowSkeleton key={`history-${i}`} variant="profile" />
              ))}
            </div>
          </TabsContent>
        </Tabs>
      </div>
    );
  }

  // Show error state
  if (isError && (!initialBorrowHistory || initialBorrowHistory.length === 0)) {
    return (
      <div className="container mx-auto px-3 py-4 sm:px-4 sm:py-6">
        <Card>
          <CardContent className="p-4 text-center sm:p-8">
            <p className="mb-2 text-base font-semibold text-red-500 sm:text-lg">
              Failed to load borrow records
            </p>
            <p className="text-xs text-gray-500 sm:text-sm">
              {error instanceof Error
                ? error.message
                : "An unknown error occurred"}
            </p>
          </CardContent>
        </Card>
      </div>
    );
  }
  const formatDate = (date: Date | string | null) => {
    if (!date) return "N/A";
    // Handle timezone-aware timestamps correctly
    const dateObj = typeof date === "string" ? new Date(date) : date;
    // Use UTC methods to avoid timezone conversion issues
    return dateObj.toLocaleDateString("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
      timeZone: "UTC", // Force UTC to match database storage
    });
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case "PENDING":
        return <Badge variant="secondary">Pending Approval</Badge>;
      case "BORROWED":
        return <Badge variant="default">Currently Borrowed</Badge>;
      case "RETURNED":
        return (
          <Badge
            variant="default"
            className="bg-green-600 text-white hover:bg-green-700"
          >
            Book Returned
          </Badge>
        );
      default:
        return <Badge variant="outline">{status}</Badge>;
    }
  };

  // CRITICAL: Memoize BorrowCard component to prevent unnecessary re-renders
  // This prevents flicker when React Query refetches but data hasn't changed
  const BorrowCard: React.FC<{
    record: BorrowRecordWithBook;
    showCountdown?: boolean;
  }> = React.memo(
    ({ record, showCountdown = false }) => {
      const handleViewDetails = () => {
        router.push(`/books/${record.book.id}`);
      };

      const handleReturnBook = () => {
        // CRITICAL: Prevent multiple clicks on the same record
        // Check if this specific record is already being returned
        if (returningRecordIdRef.current === record.id) {
          return; // This record is already being returned, ignore click
        }

        // CRITICAL: Prevent multiple clicks - check if any mutation is pending
        if (returnBookMutation.isPending) {
          return; // Already processing a return, ignore additional clicks
        }

        // Mark this record as being returned
        returningRecordIdRef.current = record.id;

        // Use mutation to return book
        returnBookMutation.mutate(
          {
            recordId: record.id,
            bookTitle: record.book.title,
          },
          {
            onError: (error) => {
              console.error("[MyProfileTabs] Return mutation error", {
                recordId: record.id,
                error,
              });
            },
          },
          // CRITICAL: No onSuccess callback needed here
          // The useReturnBook mutation already handles all cache invalidation
          // via invalidateAfterBorrowChange() which invalidates:
          // - borrows queries (including user-borrows)
          // - books queries (availability changes)
          // - reviews queries (eligibility may change)
          // - analytics queries
          // - admin queries
          // Manual invalidation here would cause redundant refetches
        );
      };

      // Calculate if book is overdue (only for BORROWED status with dueDate)
      const today = new Date();
      // Use UTC dates for consistent comparison
      const todayUTC = new Date(
        today.getTime() + today.getTimezoneOffset() * 60000,
      );
      const dueDateUTC = record.dueDate ? new Date(record.dueDate) : null;

      const isOverdue =
        record.status === "BORROWED" && dueDateUTC && todayUTC > dueDateUTC;

      // Calculate days overdue using date-level comparison (exactly like backend SQL)
      // Backend: (${now}::date - ${borrowRecords.dueDate}::date)
      // Use UTC dates to avoid timezone issues
      const todayDateUTC = new Date(
        Date.UTC(
          todayUTC.getUTCFullYear(),
          todayUTC.getUTCMonth(),
          todayUTC.getUTCDate(),
        ),
      );
      const dueDateOnlyUTC = dueDateUTC
        ? new Date(
            Date.UTC(
              dueDateUTC.getUTCFullYear(),
              dueDateUTC.getUTCMonth(),
              dueDateUTC.getUTCDate(),
            ),
          )
        : null;

      const daysOverdue =
        isOverdue && dueDateOnlyUTC
          ? Math.floor(
              (todayDateUTC.getTime() - dueDateOnlyUTC.getTime()) /
                (1000 * 60 * 60 * 24),
            )
          : 0;

      const daysRemaining =
        record.status === "BORROWED" && dueDateUTC && !isOverdue
          ? Math.ceil(
              (dueDateUTC.getTime() - todayUTC.getTime()) /
                (1000 * 60 * 60 * 24),
            )
          : 0;
      const calculatedFine = isOverdue ? daysOverdue * 1.0 : 0;

      return (
        <Card
          className={`mb-4 overflow-hidden rounded-lg border transition-colors duration-200 hover:border-[var(--mundia-navy)] ${
            record.status === "PENDING"
              ? "border-[var(--mundia-gold)] bg-[var(--surface-card)]"
              : record.status === "BORROWED" && isOverdue
                ? "border-red-200 bg-red-50"
                : record.status === "BORROWED" &&
                    daysRemaining <= 2 &&
                    !isOverdue
                  ? "border-orange-200 bg-orange-50"
                  : record.status === "BORROWED"
                    ? "border-[var(--mundia-navy)] bg-[var(--surface-card)]"
                    : record.status === "RETURNED"
                      ? "border-emerald-200 bg-emerald-50"
                      : "border-[var(--mundia-line)] bg-[var(--surface-card)]"
          }`}
        >
          <CardContent className="p-3 sm:p-4">
            <div className="flex flex-col gap-4 sm:flex-row">
              {/* Full Height Book Cover */}
              {/* CRITICAL: Don't use key prop - React.memo in BookCover handles re-render prevention
                Using key would cause component remount on every data change, causing flicker */}
              <div className="relative w-full shrink-0 rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-0)] p-3 sm:w-48">
                <BookCover
                  variant="regular"
                  coverColor={record.book.coverColor}
                  coverImage={record.book.coverUrl}
                  className="h-64 w-full sm:h-full"
                />
              </div>

              {/* Main Content */}
              <div className="min-w-0 flex-1">
                {/* Header with Status Badge */}
                <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0 flex-1">
                    <h3 className="text-base font-semibold leading-snug text-[var(--mundia-ink)] sm:text-xl">
                      {record.book.title}
                    </h3>
                    <p className="text-sm text-slate-600 sm:text-base">
                      by {record.book.author}
                    </p>
                  </div>
                  {/* Status Badge in Top Right */}
                  <div className="w-fit shrink-0 sm:ml-2">
                    {getStatusBadge(record.status)}
                  </div>
                </div>

                {/* Genre and Rating */}
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <Badge
                    variant="outline"
                    className="border-[var(--mundia-line)] bg-[var(--surface-0)] px-2 py-1 text-xs text-[var(--mundia-ink)] sm:text-sm"
                  >
                    {record.book.genre}
                  </Badge>
                  <div className="flex items-center gap-1">
                    <Star className="size-3 fill-current text-yellow-400 sm:size-4" />
                    <span className="text-xs text-yellow-400 sm:text-sm">
                      {record.book.rating}
                    </span>
                  </div>
                </div>

                {/* Compact Information */}
                <div className="mb-3 flex flex-col gap-2 rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-0)] p-3 text-xs sm:flex-row sm:flex-wrap sm:items-center sm:gap-4 sm:text-sm">
                  <div className="flex items-center gap-1">
                    <Calendar className="size-3 text-[var(--mundia-navy)] sm:size-4" />
                    <span className="font-medium text-[var(--mundia-ink)]">
                      Borrowed:
                    </span>
                    <span className="text-slate-600">
                      {formatDate(record.borrowDate)}
                    </span>
                  </div>
                  <div className="flex items-center gap-1">
                    <Clock className="size-3 text-[var(--mundia-gold-strong)] sm:size-4" />
                    <span className="font-medium text-[var(--mundia-ink)]">
                      Due:
                    </span>
                    <span className="text-slate-600">
                      {record.dueDate ? formatDate(record.dueDate) : "Not set"}
                    </span>
                  </div>
                  {record.book.isbn && (
                    <div className="flex items-center gap-1">
                      <BookOpen className="size-3 text-[var(--mundia-success-strong)] sm:size-4" />
                      <span className="font-medium text-[var(--mundia-ink)]">
                        ISBN:
                      </span>
                      <span className="font-mono text-slate-600">
                        {record.book.isbn.slice(-4)}
                      </span>
                    </div>
                  )}
                </div>

                {/* Countdown Timer */}
                {/* CRITICAL: Don't use key prop - React.memo in CountdownTimer handles re-render prevention
                  Using key would cause component remount on every data change, causing flicker */}
                {showCountdown &&
                  record.status === "BORROWED" &&
                  record.dueDate && (
                    <div className="mb-2">
                      <CountdownTimer
                        dueDate={record.dueDate}
                        borrowDate={record.borrowDate}
                      />
                    </div>
                  )}

                {/* Enhanced Status Messages */}
                <div className="mb-2">
                  {record.status === "PENDING" && (
                    <div className="flex items-center gap-1.5 rounded bg-yellow-500/10 px-2 py-1 sm:gap-2">
                      <Clock className="size-3 text-yellow-400 sm:size-4" />
                      <span className="text-xs text-yellow-400 sm:text-sm">
                        Awaiting admin approval
                      </span>
                    </div>
                  )}

                  {record.status === "BORROWED" && record.dueDate && (
                    <div
                      className={`flex items-center gap-1.5 rounded px-2 py-1 sm:gap-2 ${
                        isOverdue
                          ? "bg-red-500/10"
                          : daysRemaining <= 2
                            ? "bg-orange-500/10"
                            : "bg-blue-500/10"
                      }`}
                    >
                      {isOverdue ? (
                        <AlertTriangle className="size-3 text-red-400 sm:size-4" />
                      ) : daysRemaining <= 2 ? (
                        <AlertTriangle className="size-3 text-orange-600 sm:size-4" />
                      ) : (
                        <BookOpen className="size-3 text-blue-400 sm:size-4" />
                      )}
                      <span
                        className={`text-xs sm:text-sm ${
                          isOverdue
                            ? "text-red-400"
                            : daysRemaining <= 2
                              ? "text-orange-600"
                              : "text-blue-400"
                        }`}
                      >
                        {isOverdue
                          ? `Overdue: ${daysOverdue} days late`
                          : daysRemaining <= 2
                            ? `Due soon: ${daysRemaining} day${daysRemaining === 1 ? "" : "s"} left`
                            : `Due on ${formatDate(record.dueDate)}`}
                      </span>
                    </div>
                  )}

                  {record.status === "RETURNED" && (
                    <div className="flex items-center gap-1.5 rounded bg-green-500/10 px-2 py-1 sm:gap-2">
                      <Calendar className="size-3 text-green-600 sm:size-4" />
                      <span className="text-xs text-green-600 sm:text-sm">
                        Successfully returned
                      </span>
                    </div>
                  )}
                </div>

                {/* Fine and Renewal Info */}
                <div className="mb-2 flex flex-wrap gap-2">
                  {(record.fineAmount > 0 || calculatedFine > 0) && (
                    <div className="flex items-center gap-1 rounded bg-red-500/10 px-1.5 py-0.5 sm:px-2 sm:py-1">
                      <AlertTriangle className="size-3 text-red-400 sm:size-4" />
                      <span className="text-xs font-medium text-red-400 sm:text-sm">
                        $
                        {(record.fineAmount > 0
                          ? record.fineAmount
                          : calculatedFine
                        ).toFixed(2)}
                      </span>
                      <span className="text-xs text-red-300/70 sm:text-sm">
                        {isOverdue ? "overdue fine" : "fine"}
                      </span>
                    </div>
                  )}

                  {record.renewalCount > 0 && (
                    <div className="flex items-center gap-1 rounded bg-purple-500/10 px-1.5 py-0.5 sm:px-2 sm:py-1">
                      <RotateCcw className="size-3 text-purple-400 sm:size-4" />
                      <span className="text-xs font-medium text-purple-400 sm:text-sm">
                        {record.renewalCount}
                      </span>
                      <span className="text-xs text-purple-300/70 sm:text-sm">
                        renewals
                      </span>
                    </div>
                  )}
                </div>

                {/* Action Buttons */}
                <div className="flex flex-wrap gap-2">
                  {record.status === "BORROWED" && (
                    <RenewalRequestButton
                      borrowRecordId={record.id}
                      bookTitle={record.book.title}
                    />
                  )}

                  {record.status === "BORROWED" && (
                    <button
                      onClick={handleReturnBook}
                      disabled={
                        (returnBookMutation.isPending &&
                          returningRecordIdRef.current === record.id) ||
                        returningRecordIdRef.current === record.id
                      }
                      className={`flex min-h-11 items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 sm:px-3 sm:text-sm ${
                        isOverdue
                          ? "bg-red-600 text-white hover:bg-red-700"
                          : "bg-orange-600 text-white hover:bg-orange-700"
                      }`}
                    >
                      <RotateCcw className="size-3 sm:size-4" />
                      <span>
                        {returningRecordIdRef.current === record.id
                          ? "Returning..."
                          : "Return Book"}
                      </span>
                    </button>
                  )}

                  {record.status !== "RETURNED" && (
                    <button
                      onClick={handleViewDetails}
                      className="flex min-h-11 items-center gap-1 rounded-lg bg-blue-600 px-2.5 py-1.5 text-xs font-medium text-white transition-colors hover:bg-blue-700 sm:px-3 sm:text-sm"
                    >
                      <Eye className="size-3 sm:size-4" />
                      <span>View Details</span>
                    </button>
                  )}

                  {record.status === "RETURNED" && (
                    <button
                      onClick={handleViewDetails}
                      className="flex min-h-11 items-center gap-1 rounded-lg bg-green-600 px-2.5 py-1.5 text-xs font-medium text-white transition-colors hover:bg-green-700 sm:px-3 sm:text-sm"
                    >
                      <Star className="size-3 sm:size-4" />
                      <span>Review Book</span>
                    </button>
                  )}
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      );
    },
    (prevProps, nextProps) => {
      // CRITICAL: Custom comparison to prevent re-renders when data hasn't actually changed
      // React.memo comparison returns TRUE if props are EQUAL (skip re-render)
      // Returns FALSE if props are DIFFERENT (re-render)
      // Compare all critical fields that affect rendering

      // Quick reference equality check first (fastest)
      if (
        prevProps.record === nextProps.record &&
        prevProps.showCountdown === nextProps.showCountdown
      ) {
        return true; // Same reference, skip re-render
      }

      // Deep comparison for critical fields
      const recordEqual =
        prevProps.record.id === nextProps.record.id &&
        prevProps.record.status === nextProps.record.status &&
        prevProps.record.borrowDate?.getTime() ===
          nextProps.record.borrowDate?.getTime() &&
        prevProps.record.dueDate?.getTime() ===
          nextProps.record.dueDate?.getTime() &&
        prevProps.record.returnDate?.getTime() ===
          nextProps.record.returnDate?.getTime() &&
        prevProps.record.fineAmount === nextProps.record.fineAmount &&
        prevProps.record.book.id === nextProps.record.book.id &&
        prevProps.record.book.title === nextProps.record.book.title &&
        prevProps.record.book.author === nextProps.record.book.author &&
        prevProps.record.book.coverUrl === nextProps.record.book.coverUrl &&
        prevProps.record.book.coverColor === nextProps.record.book.coverColor &&
        prevProps.record.book.genre === nextProps.record.book.genre &&
        prevProps.record.book.rating === nextProps.record.book.rating;

      const countdownEqual =
        prevProps.showCountdown === nextProps.showCountdown;

      // Return true if all props are equal (skip re-render)
      return recordEqual && countdownEqual;
    },
  );

  // Set display name for React DevTools
  BorrowCard.displayName = "BorrowCard";

  return (
    <div className="container mx-auto">
      <h1 className="mb-4 text-2xl font-semibold tracking-tight text-[var(--mundia-ink)] sm:mb-6 sm:text-3xl">
        My Borrowing History
      </h1>

      <Tabs
        value={activeTabValue}
        onValueChange={setActiveTabValue}
        className="w-full"
        suppressHydrationWarning
      >
        <TabsList className="flex h-auto w-full flex-wrap gap-6 border-b border-[var(--mundia-line)] bg-transparent p-0">
          <TabsTrigger
            value="active"
            className="rounded-none border-b-2 border-transparent bg-transparent px-0 py-3 text-sm text-[var(--mundia-muted)] shadow-none data-[state=active]:border-[var(--mundia-navy)] data-[state=active]:bg-transparent data-[state=active]:text-[var(--mundia-navy)] data-[state=active]:shadow-none"
          >
            <span className="block sm:inline">
              <span className="block sm:inline">Active</span>
              <span className="block sm:inline sm:before:content-['\00a0']">
                Borrows
              </span>
              <span className="block sm:inline sm:before:content-['\00a0']">
                ({activeBorrows.length})
              </span>
            </span>
          </TabsTrigger>
          <TabsTrigger
            value="pending"
            className="rounded-none border-b-2 border-transparent bg-transparent px-0 py-3 text-sm text-[var(--mundia-muted)] shadow-none data-[state=active]:border-[var(--mundia-navy)] data-[state=active]:bg-transparent data-[state=active]:text-[var(--mundia-navy)] data-[state=active]:shadow-none"
          >
            <span className="block sm:inline">
              <span className="block sm:inline">Pending</span>
              <span className="block sm:inline sm:before:content-['\00a0']">
                Requests
              </span>
              <span className="block sm:inline sm:before:content-['\00a0']">
                ({pendingRequests.length})
              </span>
            </span>
          </TabsTrigger>
          <TabsTrigger
            value="history"
            className="rounded-none border-b-2 border-transparent bg-transparent px-0 py-3 text-sm text-[var(--mundia-muted)] shadow-none data-[state=active]:border-[var(--mundia-navy)] data-[state=active]:bg-transparent data-[state=active]:text-[var(--mundia-navy)] data-[state=active]:shadow-none"
          >
            <span className="block sm:inline">
              <span className="block sm:inline">Borrow</span>
              <span className="block sm:inline sm:before:content-['\00a0']">
                History
              </span>
              <span className="block sm:inline sm:before:content-['\00a0']">
                ({borrowHistory.length})
              </span>
            </span>
          </TabsTrigger>
        </TabsList>

        <TabsContent value="active" className="mt-5">
          <div className="space-y-3 sm:space-y-4">
            <h2 className="text-base font-semibold text-[var(--mundia-ink)] sm:text-xl">
              Currently Borrowed Books
            </h2>
            {activeBorrows.length === 0 ? (
              <div className="rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card)] p-5">
                <p className="text-sm text-slate-700 sm:text-base">
                  No active borrows. Start with the collection and request a
                  book when you find the right copy.
                </p>
                <Link
                  href="/all-books"
                  className="mt-3 inline-flex text-sm font-semibold text-[var(--mundia-navy)] hover:underline"
                >
                  Browse the collection
                </Link>
              </div>
            ) : (
              activeBorrows.map((record) => (
                <BorrowCard
                  key={record.id}
                  record={record}
                  showCountdown={true}
                />
              ))
            )}
          </div>
        </TabsContent>

        <TabsContent value="pending" className="mt-5">
          <div className="space-y-3 sm:space-y-4">
            <h2 className="text-base font-semibold text-[var(--mundia-ink)] sm:text-xl">
              Pending Approval
            </h2>
            {pendingRequests.length === 0 ? (
              <div className="rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card)] p-5">
                <p className="text-sm text-slate-700 sm:text-base">
                  No pending requests. Books you ask to borrow will appear here
                  while the library reviews them.
                </p>
                <Link
                  href="/all-books"
                  className="mt-3 inline-flex text-sm font-semibold text-[var(--mundia-navy)] hover:underline"
                >
                  Find a book to request
                </Link>
              </div>
            ) : (
              pendingRequests.map((record) => (
                <BorrowCard key={record.id} record={record} />
              ))
            )}
          </div>
        </TabsContent>

        <TabsContent value="history" className="mt-5">
          <div className="space-y-3 sm:space-y-4">
            <h2 className="text-base font-semibold text-[var(--mundia-ink)] sm:text-xl">
              Complete Borrow History
            </h2>
            {borrowHistory.length === 0 ? (
              <div className="rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card)] p-5">
                <p className="text-sm text-slate-700 sm:text-base">
                  No borrow history yet. Returned books and review prompts will
                  collect here after your first loan.
                </p>
                <Link
                  href="/all-books"
                  className="mt-3 inline-flex text-sm font-semibold text-[var(--mundia-navy)] hover:underline"
                >
                  Browse available books
                </Link>
              </div>
            ) : (
              <>
                <section className="mb-4 rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card)] p-4">
                  <h3 className="text-base font-semibold text-[var(--mundia-ink)] sm:text-lg">
                    Borrow statistics
                  </h3>
                  <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-3 text-sm md:grid-cols-4">
                    <div>
                      <dt className="text-slate-500">Total borrows</dt>
                      <dd className="mt-1 text-xl font-semibold text-[var(--mundia-ink)]">
                        {allBorrows.length}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Pending</dt>
                      <dd className="mt-1 text-xl font-semibold text-[var(--mundia-navy)]">
                        {pendingRequests.length}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Active</dt>
                      <dd className="mt-1 text-xl font-semibold text-[var(--mundia-gold-strong)]">
                        {activeBorrows.length}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Returned</dt>
                      <dd className="mt-1 text-xl font-semibold text-emerald-700">
                        {borrowHistory.length}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">With fines</dt>
                      <dd className="mt-1 text-xl font-semibold text-red-700">
                        {
                          allBorrows.filter((r) => {
                            const today = new Date();
                            const todayUTC = new Date(
                              today.getTime() +
                                today.getTimezoneOffset() * 60000,
                            );
                            const dueDateUTC = r.dueDate
                              ? new Date(r.dueDate)
                              : null;

                            const isOverdue =
                              r.status === "BORROWED" &&
                              dueDateUTC &&
                              todayUTC > dueDateUTC;

                            if (isOverdue && dueDateUTC) {
                              const todayDateUTC = new Date(
                                Date.UTC(
                                  todayUTC.getUTCFullYear(),
                                  todayUTC.getUTCMonth(),
                                  todayUTC.getUTCDate(),
                                ),
                              );
                              const dueDateOnlyUTC = new Date(
                                Date.UTC(
                                  dueDateUTC.getUTCFullYear(),
                                  dueDateUTC.getUTCMonth(),
                                  dueDateUTC.getUTCDate(),
                                ),
                              );
                              const daysOverdue = Math.floor(
                                (todayDateUTC.getTime() -
                                  dueDateOnlyUTC.getTime()) /
                                  (1000 * 60 * 60 * 24),
                              );
                              return daysOverdue > 0;
                            }

                            return (
                              (typeof r.fineAmount === "number"
                                ? r.fineAmount
                                : parseFloat(String(r.fineAmount)) || 0) > 0
                            );
                          }).length
                        }
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Total fines</dt>
                      <dd className="mt-1 text-xl font-semibold text-red-700">
                        $
                        {allBorrows
                          .reduce((sum, r) => {
                            const today = new Date();
                            const todayUTC = new Date(
                              today.getTime() +
                                today.getTimezoneOffset() * 60000,
                            );
                            const dueDateUTC = r.dueDate
                              ? new Date(r.dueDate)
                              : null;

                            const isOverdue =
                              r.status === "BORROWED" &&
                              dueDateUTC &&
                              todayUTC > dueDateUTC;

                            if (isOverdue && dueDateUTC) {
                              const todayDateUTC = new Date(
                                Date.UTC(
                                  todayUTC.getUTCFullYear(),
                                  todayUTC.getUTCMonth(),
                                  todayUTC.getUTCDate(),
                                ),
                              );
                              const dueDateOnlyUTC = new Date(
                                Date.UTC(
                                  dueDateUTC.getUTCFullYear(),
                                  dueDateUTC.getUTCMonth(),
                                  dueDateUTC.getUTCDate(),
                                ),
                              );
                              const daysOverdue = Math.floor(
                                (todayDateUTC.getTime() -
                                  dueDateOnlyUTC.getTime()) /
                                  (1000 * 60 * 60 * 24),
                              );
                              return sum + daysOverdue * 1.0;
                            }

                            return (
                              sum +
                              (typeof r.fineAmount === "number"
                                ? r.fineAmount
                                : parseFloat(String(r.fineAmount)) || 0)
                            );
                          }, 0)
                          .toFixed(2)}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Renewals</dt>
                      <dd className="mt-1 text-xl font-semibold text-[var(--mundia-burgundy)]">
                        {allBorrows.reduce(
                          (sum, r) => sum + (r.renewalCount || 0),
                          0,
                        )}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Reviews</dt>
                      <dd className="mt-1 text-xl font-semibold text-[var(--mundia-ink)]">
                        {totalReviews}
                      </dd>
                    </div>
                  </dl>
                </section>

                {/* History List */}
                {borrowHistory
                  .sort(
                    (a, b) =>
                      new Date(b.createdAt || 0).getTime() -
                      new Date(a.createdAt || 0).getTime(),
                  )
                  .map((record) => (
                    <BorrowCard key={record.id} record={record} />
                  ))}
              </>
            )}
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default MyProfileTabs;
