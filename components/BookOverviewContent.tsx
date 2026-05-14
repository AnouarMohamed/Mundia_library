"use client";

/**
 * BookOverviewContent Component
 *
 * Client component that displays book overview information.
 * Uses React Query to fetch book data dynamically, ensuring immediate updates.
 *
 * Features:
 * - Uses useBook hook to fetch book data with SSR initial data support
 * - Displays all book information including availableCopies, totalCopies, isActive
 * - Updates immediately when book data changes (via cache invalidation)
 * - Integrates with BookBorrowStats and BookBorrowButton for dynamic updates
 */

import React from "react";
import BookCover from "@/components/BookCover";
import BookBorrowStats from "@/components/BookBorrowStats";
import BookBorrowButton from "@/components/BookBorrowButton";
import { Button } from "@/components/ui/button";
import { BookOpen, AlertCircle } from "lucide-react";
import Link from "next/link";
import { useBook } from "@/hooks/useQueries";
import BookSkeleton from "@/components/skeletons/BookSkeleton";
import type { BorrowRecord } from "@/lib/services/borrows";
import type { ReviewEligibility } from "@/lib/services/reviews";

interface BookOverviewContentProps {
  /**
   * Book ID
   */
  bookId: string;
  /**
   * User ID
   */
  userId: string;
  /**
   * User status (APPROVED, PENDING, etc.)
   */
  userStatus?: string | null;
  /**
   * Whether this is a detail page
   */
  isDetailPage?: boolean;
  /**
   * Initial book data from SSR (prevents duplicate fetch)
   */
  initialBook: Book;
  /**
   * Initial borrow statistics from SSR (prevents duplicate fetch)
   */
  initialStats?: {
    totalBorrows: number;
    activeBorrows: number;
    returnedBorrows: number;
  };
  /**
   * Initial user borrows from SSR (prevents duplicate fetch, ensures correct button state on first load)
   */
  initialUserBorrows?: BorrowRecord[];
  /**
   * Initial review eligibility from SSR (prevents duplicate fetch, ensures correct button state on first load)
   */
  initialReviewEligibility?: ReviewEligibility;
}

/**
 * Client-rendered book overview with borrow actions.
 */
const BookOverviewContent: React.FC<BookOverviewContentProps> = ({
  bookId,
  userId,
  userStatus,
  isDetailPage = false,
  initialBook,
  initialStats,
  initialUserBorrows,
  initialReviewEligibility,
}) => {
  // Use React Query hook with SSR initial data
  const {
    data: book,
    isLoading,
    isError,
    error,
  } = useBook(bookId, initialBook);

  // Show skeleton while loading (only if no initial data)
  if (isLoading && !initialBook) {
    return <BookSkeleton showDetails={false} />;
  }

  // Show error state
  if (isError && !initialBook) {
    return (
      <div className="container mx-auto px-3 py-4 sm:px-4 sm:py-6">
        <div className="rounded-lg border border-red-500 bg-red-50 p-4 text-center sm:p-8">
          <p className="mb-2 text-base font-semibold text-red-500 sm:text-lg">
            Failed to load book
          </p>
          <p className="text-xs text-gray-500 sm:text-sm">
            {error instanceof Error
              ? error.message
              : "An unknown error occurred"}
          </p>
        </div>
      </div>
    );
  }

  // CRITICAL: Always prefer React Query data over initialBook
  // React Query data is fresh and updates immediately after mutations
  // initialBook is only used as fallback during initial load
  const bookData = book ?? initialBook;

  if (!bookData) {
    return null;
  }

  const {
    title,
    author,
    genre,
    rating,
    totalCopies,
    availableCopies,
    description,
    coverColor,
    coverUrl,
    id,
    isbn,
    publicationYear,
    publisher,
    language,
    pageCount,
    edition,
    isActive,
    createdAt,
    updatedAt,
  } = bookData;

  const detailFields = [
    { label: "Published", value: publicationYear ?? "Not listed" },
    { label: "Publisher", value: publisher || "Not listed" },
    { label: "Language", value: language || "Not listed" },
    { label: "Pages", value: pageCount ?? "Not listed" },
    { label: "Edition", value: edition || "Not listed" },
    { label: "ISBN", value: isbn || "Not listed" },
  ];

  const formattedRating =
    typeof rating === "number" ? rating.toFixed(1) : String(rating ?? "N/A");

  const formattedCreatedAt = createdAt
    ? new Date(createdAt).toLocaleDateString("en-US", {
        year: "numeric",
        month: "long",
        day: "numeric",
      })
    : "N/A";

  const formattedUpdatedAt = updatedAt
    ? new Date(updatedAt).toLocaleDateString("en-US", {
        year: "numeric",
        month: "long",
        day: "numeric",
      })
    : "N/A";

  return (
    <section className="book-overview motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-4 motion-safe:duration-500">
      <div className="relative z-10 flex min-w-0 flex-1 flex-col gap-5 sm:gap-6">
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-full border border-[var(--mundia-line)] bg-[var(--mundia-paper)] px-3 py-1 text-sm text-[var(--mundia-muted)]">
            {genre}
          </span>
          {!isActive && (
            <span className="inline-flex items-center gap-1 rounded-full border border-red-200 bg-red-50 px-3 py-1 text-sm text-red-700">
              <AlertCircle className="size-3.5" />
              Currently unavailable
            </span>
          )}
        </div>

        <h1 className="break-words">{title}</h1>

        <p className="text-sm text-slate-600 sm:text-base">
          By{" "}
          <span className="font-semibold text-[var(--mundia-ink)]">
            {author}
          </span>
        </p>

        <div className="flex flex-wrap gap-2 sm:gap-3">
          <span className="rounded-full border border-[var(--mundia-line)] bg-[var(--surface-0)] px-3 py-1.5 text-xs text-slate-700 sm:text-sm">
            <img
              src="/icons/star.svg"
              alt="star"
              width={18}
              height={18}
              className="size-4"
            />
            {formattedRating}
          </span>
          <span className="rounded-full border border-[var(--mundia-line)] bg-[var(--surface-0)] px-3 py-1.5 text-xs text-slate-700 sm:text-sm">
            {availableCopies} available of {totalCopies}
          </span>
        </div>

        <dl className="grid gap-x-8 gap-y-3 border-y border-[var(--mundia-line)] py-4 text-sm sm:grid-cols-2">
          {detailFields.map((item) => (
            <div key={item.label} className="flex justify-between gap-4">
              <dt className="text-[var(--mundia-muted)]">{item.label}</dt>
              <dd className="max-w-[14rem] truncate text-right font-medium text-[var(--mundia-ink)]">
                {item.value}
              </dd>
            </div>
          ))}
          <div className="flex justify-between gap-4">
            <dt className="text-[var(--mundia-muted)]">Catalog record</dt>
            <dd className="text-right font-medium text-[var(--mundia-ink)]">
              Added {formattedCreatedAt}
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-[var(--mundia-muted)]">Last update</dt>
            <dd className="text-right font-medium text-[var(--mundia-ink)]">
              {formattedUpdatedAt}
            </dd>
          </div>
        </dl>

        <BookBorrowStats
          bookId={id}
          initialBook={bookData}
          initialStats={initialStats}
        />

        <div className="rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-0)] px-4 py-4 sm:px-5">
          <p className="book-description">{description}</p>
        </div>

        {userId && (
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:gap-4">
            {isDetailPage ? (
              <BookBorrowButton
                bookId={id}
                userId={userId}
                bookTitle={title}
                availableCopies={availableCopies}
                isActive={isActive}
                userStatus={userStatus}
                isDetailPage={true}
                initialUserBorrows={initialUserBorrows}
                initialReviewEligibility={initialReviewEligibility}
              />
            ) : (
              <>
                <BookBorrowButton
                  bookId={id}
                  userId={userId}
                  bookTitle={title}
                  availableCopies={availableCopies}
                  isActive={isActive}
                  userStatus={userStatus}
                  isDetailPage={false}
                  initialUserBorrows={initialUserBorrows}
                  initialReviewEligibility={initialReviewEligibility}
                />
                <Button
                  asChild
                  className="mt-0 min-h-12 w-full rounded-lg bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)] sm:w-fit"
                >
                  <Link href={`/books/${id}`}>
                    <BookOpen className="size-4 text-white sm:size-5" />
                    <span className="text-sm font-semibold">Book Details</span>
                  </Link>
                </Button>
              </>
            )}
          </div>
        )}
      </div>

      <div className="relative z-10 flex flex-1 items-center justify-center">
        <div className="relative">
          <div className="pointer-events-none absolute -left-5 -top-5 h-[calc(100%+2.5rem)] w-[calc(100%+2.5rem)] rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-0)]" />
          <BookCover
            variant="wide"
            className="z-10"
            coverColor={coverColor}
            coverImage={coverUrl}
          />

          <div className="absolute left-14 top-8 -z-10 rotate-12 opacity-45 max-sm:hidden">
            <BookCover
              variant="wide"
              coverColor={coverColor}
              coverImage={coverUrl}
            />
          </div>
        </div>
      </div>
    </section>
  );
};

export default BookOverviewContent;
