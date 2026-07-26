/**
 * BookDetailContent Component
 * 
 * Orchestrates the display of detailed information for a single book.
 * Includes video trailers, summaries, and user reviews.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React from "react";
import BookVideo from "@/components/BookVideo";
import ReviewsSection from "@/components/ReviewsSection";
import BookSkeleton from "@/components/skeletons/BookSkeleton";
import { useBook, useBookReviews } from "@/hooks/useQueries";

/**
 * Props for BookDetailContent
 */
interface BookDetailContentProps {
  /**
   * Unique identifier of the book (UUID)
   */
  bookId: string;
  /**
   * Unique identifier of the current user (optional)
   */
  userId?: string;
  /**
   * Initial book data from SSR to prevent layout shift and duplicate fetches
   */
  initialBook?: Book;
  /**
   * Initial reviews data from SSR
   */
  initialReviews?: Array<{
    id: string;
    rating: number;
    comment: string;
    createdAt: Date | null;
    updatedAt: Date | null;
    /** Privacy-preserving reviewer label, not an account holder's legal name. */
    userFullName: string;
    isOwner: boolean;
  }>;
}

/**
 * BookDetailContent
 * 
 * Client component that displays book details, video, summary, and reviews.
 * Uses React Query for data fetching and caching, with SSR initial data support.
 *
 * Features:
 * - Uses useBook and useBookReviews hooks with initialData from SSR
 * - Displays skeleton loaders while fetching
 * - Shows error state if fetch fails
 * - Integrates with BookOverview, BookVideo, and ReviewsSection components
 */
const BookDetailContent: React.FC<BookDetailContentProps> = ({
  bookId,
  userId: _userId,
  initialBook,
  initialReviews,
}) => {
  // Use React Query hooks with SSR initial data to ensure fast first paint
  const {
    data: book,
    isLoading: isLoadingBook,
    isError: isErrorBook,
    error: bookError,
  } = useBook(bookId, initialBook);

  const {
    data: reviews,
    isLoading: isLoadingReviews,
    isError: isErrorReviews,
    error: reviewsError,
  } = useBookReviews(bookId, initialReviews);

  // Show skeleton while loading (only if no initial data is available)
  if (
    (isLoadingBook && !initialBook) ||
    (isLoadingReviews && !initialReviews)
  ) {
    return <BookSkeleton showDetails={true} />;
  }

  // Handle failure to load core book data
  if (isErrorBook || !book) {
    return (
      <div className="container mx-auto px-3 py-4 sm:px-4 sm:py-6">
        <div className="rounded-lg border border-red-500 bg-red-50 p-4 text-center sm:p-8">
          <p className="mb-2 text-base font-semibold text-red-500 sm:text-lg">
            Failed to load book
          </p>
          <p className="text-xs text-gray-500 sm:text-sm">
            {bookError instanceof Error
              ? bookError.message
              : "An unknown error occurred"}
          </p>
        </div>
      </div>
    );
  }

  // CRITICAL: Always prefer React Query data. The error guard above guarantees it.
  const bookData = book;

  return (
    <div className="book-details">
      <div className="flex-[1.5] w-full min-w-0 max-w-full overflow-hidden">
        {/* Video Section - Displays book trailer if available */}
        <section className="flex flex-col gap-4 sm:gap-7">
          <h3 className="text-base font-semibold text-[var(--mundia-navy)] sm:text-lg">
            Video
          </h3>
          <BookVideo videoUrl={bookData.videoUrl} />
        </section>

        {/* Summary Section - Renders summary text with preserved line breaks */}
        <section className="mt-6 flex flex-col gap-4 sm:mt-10 sm:gap-7">
          <h3 className="text-base font-semibold text-[var(--mundia-navy)] sm:text-lg">
            Summary
          </h3>
          <div className="space-y-3 break-words text-base text-slate-700 sm:space-y-5 sm:text-xl">
            {bookData.summary?.split("\n").map((line: string, i: number) => (
              <p key={i} className="break-words">
                {line}
              </p>
            ))}
          </div>
        </section>

        {/* Reviews Section - Displays user feedback and ratings */}
        <section className="mt-6 flex flex-col gap-4 sm:mt-10 sm:gap-7">
          {/* 
            CRITICAL PERFORMANCE: Pass React Query data to ReviewsSection.
            React Query data updates immediately after mutations (adding/editing reviews),
            whereas initialReviews is static SSR data.
          */}
          <ReviewsSection
            bookId={bookId}
            reviews={reviews ?? initialReviews ?? []}
          />
          
          {/* Show non-blocking error message for reviews if book loaded successfully */}
          {isErrorReviews && (
            <div className="rounded-lg border border-yellow-500 bg-yellow-50 p-3 text-yellow-800 sm:p-4">
              <p className="text-sm font-semibold sm:text-base">
                Failed to load reviews
              </p>
              <p className="text-xs sm:text-sm">
                {reviewsError instanceof Error
                  ? reviewsError.message
                  : "An unknown error occurred"}
              </p>
            </div>
          )}
        </section>
      </div>

      {/* Future expansion: Similar Books section could be added here */}
    </div>
  );
};

export default BookDetailContent;
