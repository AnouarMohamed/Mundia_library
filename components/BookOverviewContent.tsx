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
    { label: "ISBN", value: isbn || "N/A" },
    { label: "Published", value: publicationYear || "N/A" },
    { label: "Publisher", value: publisher || "N/A" },
    { label: "Language", value: language || "N/A" },
    { label: "Pages", value: pageCount || "N/A" },
    { label: "Edition", value: edition || "N/A" },
    { label: "Total Copies", value: totalCopies || "N/A" },
    { label: "Available", value: availableCopies || "N/A" },
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
      <div className="pointer-events-none absolute -left-20 -top-28 h-72 w-72 rounded-full bg-primary/15 blur-3xl" />
      <div className="pointer-events-none absolute -right-12 bottom-8 h-52 w-52 rounded-full bg-blue-200/20 blur-3xl" />

      <div className="relative z-10 flex min-w-0 flex-1 flex-col gap-5 sm:gap-6">
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-full border border-primary/40 bg-primary/20 px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.22em] text-primary sm:text-xs">
            Featured Book
          </span>
          {!isActive && (
            <span className="inline-flex items-center gap-1 rounded-full border border-red-300/30 bg-red-500/20 px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-red-200 sm:text-xs">
              <AlertCircle className="size-3.5" />
              Currently Unavailable
            </span>
          )}
        </div>

        <h1 className="break-words">{title}</h1>

        <p className="text-sm text-light-100/85 sm:text-base">
          By <span className="font-semibold text-light-200">{author}</span>
        </p>

        <div className="flex flex-wrap gap-2 sm:gap-3">
          <span className="rounded-full border border-white/15 bg-white/5 px-3 py-1.5 text-xs text-light-100/90 sm:text-sm">
            Category: <span className="font-semibold text-light-200">{genre}</span>
          </span>
          <span className="inline-flex items-center gap-1 rounded-full border border-white/15 bg-white/5 px-3 py-1.5 text-xs text-light-100/90 sm:text-sm">
            <img src="/icons/star.svg" alt="star" width={18} height={18} className="size-4" />
            {formattedRating}
          </span>
          <span className="rounded-full border border-white/15 bg-white/5 px-3 py-1.5 text-xs text-light-100/90 sm:text-sm">
            {availableCopies} available of {totalCopies}
          </span>
        </div>

        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 sm:gap-3">
          {detailFields.map((item) => (
            <div
              key={item.label}
              className="rounded-xl border border-white/10 bg-white/5 px-4 py-3"
            >
              <p className="text-[11px] uppercase tracking-[0.18em] text-light-100/60">
                {item.label}
              </p>
              <p className="mt-1 text-sm font-semibold text-light-100 sm:text-base">
                {item.value}
              </p>
            </div>
          ))}
        </div>

        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 sm:gap-3">
          <div className="rounded-xl border border-white/10 bg-white/5 px-4 py-3">
            <p className="text-[11px] uppercase tracking-[0.18em] text-light-100/60">
              Added to Library
            </p>
            <p className="mt-1 text-sm font-semibold text-light-100 sm:text-base">
              {formattedCreatedAt}
            </p>
          </div>
          <div className="rounded-xl border border-white/10 bg-white/5 px-4 py-3">
            <p className="text-[11px] uppercase tracking-[0.18em] text-light-100/60">
              Last Updated
            </p>
            <p className="mt-1 text-sm font-semibold text-light-100 sm:text-base">
              {formattedUpdatedAt}
            </p>
          </div>
        </div>

        <BookBorrowStats
          bookId={id}
          initialBook={bookData}
          initialStats={initialStats}
        />

        <div className="rounded-2xl border border-white/10 bg-white/5 px-4 py-4 sm:px-5">
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
                  className="mt-0 min-h-12 w-full rounded-xl border border-primary/50 bg-primary text-dark-100 hover:bg-primary/95 sm:w-fit"
                >
                  <Link href={`/books/${id}`}>
                    <BookOpen className="size-4 text-dark-100 sm:size-5" />
                    <p className="font-bebas-neue text-base tracking-[0.08em] text-dark-100 sm:text-xl">
                      Book Details
                    </p>
                  </Link>
                </Button>
              </>
            )}
          </div>
        )}
      </div>

      <div className="relative z-10 flex flex-1 items-center justify-center">
        <div className="relative">
          <div className="pointer-events-none absolute -left-6 -top-6 h-24 w-24 rounded-full bg-primary/25 blur-2xl" />
          <BookCover
            variant="wide"
            className="z-10 drop-shadow-[0_18px_26px_rgba(0,0,0,0.45)]"
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
