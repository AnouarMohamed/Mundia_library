/**
 * HomeRecommendations Component
 * 
 * Fetches and displays book recommendations on the landing page.
 * Utilizes React Query with SSR initial data to provide a seamless, 
 * flicker-free loading experience.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React from "react";
import BookList from "@/components/BookList";
import BookCardSkeleton from "@/components/skeletons/BookCardSkeleton";
import { useBookRecommendations } from "@/hooks/useQueries";

/**
 * Props for HomeRecommendations
 */
interface HomeRecommendationsProps {
  /**
   * Initial recommended books from SSR to prevent duplicate fetch and layout shift
   */
  initialRecommendations: Book[];
  /**
   * Optional User ID to provide personalized recommendations
   */
  userId?: string;
  /**
   * Maximum number of books to display
   * @default 6
   */
  limit?: number;
}

/**
 * HomeRecommendations
 * 
 * Home page recommendations with SSR-friendly data.
 * Features:
 * - Uses useBookRecommendations hook with initialData from SSR
 * - Displays skeleton loaders while fetching
 * - Shows error state if fetch fails
 * - Integrates with BookList component for display
 */
const HomeRecommendations: React.FC<HomeRecommendationsProps> = ({
  initialRecommendations,
  userId,
  limit = 6,
}) => {
  // Fetch live recommendations using React Query, leveraging SSR data for hydration
  const {
    data: recommendedBooks,
    isLoading,
    isError,
    error,
  } = useBookRecommendations(userId, limit, initialRecommendations);

  // Show skeleton while loading (only if no initial data is available to bridge the gap)
  if (
    isLoading &&
    (!initialRecommendations || initialRecommendations.length === 0)
  ) {
    return (
      <section className="mt-12 fade-in-up sm:mt-20">
        <h2 className="text-2xl font-semibold tracking-tight text-[var(--mundia-ink)] sm:text-3xl">
          Book Recommendations
        </h2>
        <ul className="book-list">
          {[...Array(6)].map((_, index) => (
            <BookCardSkeleton key={`skeleton-${index}`} />
          ))}
        </ul>
      </section>
    );
  }

  // Handle failure to fetch recommendations
  if (isError) {
    return (
      <section className="mt-12 fade-in-up sm:mt-20">
        <h2 className="text-2xl font-semibold tracking-tight text-[var(--mundia-ink)] sm:text-3xl">
          Book Recommendations
        </h2>
        <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-red-700 sm:mt-4 sm:p-4">
          <p className="text-sm font-semibold sm:text-base">
            Failed to load recommendations
          </p>
          <p className="text-xs text-red-600 sm:text-sm">
            {error instanceof Error
              ? error.message
              : "An unknown error occurred"}
          </p>
        </div>
      </section>
    );
  }

  /**
   * Data Selection Strategy:
   * CRITICAL: Always prefer React Query data over initialRecommendations.
   * React Query data is fresh and updates immediately after mutations.
   * initialRecommendations is only used as fallback during initial load.
   */
  const books = recommendedBooks ?? initialRecommendations ?? [];

  return (
    <BookList
      title="Book Recommendations"
      books={books}
      containerClassName="mt-12 sm:mt-20"
      showViewAllButton={true}
    />
  );
};

export default HomeRecommendations;
