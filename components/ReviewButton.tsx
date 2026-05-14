"use client";

/**
 * ReviewButton Component
 *
 * Button component for reviewing books. Uses React Query hook for eligibility check.
 * Integrates with useReviewEligibility hook and useCreateReview mutation.
 *
 * Features:
 * - Uses useReviewEligibility hook for eligibility check
 * - Shows loading state while checking eligibility
 * - Displays appropriate button state based on eligibility
 * - Opens ReviewFormDialog when eligible
 */

import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import ReviewFormDialog from "@/components/ReviewFormDialog";
import { MessageCircle } from "lucide-react";
import { useReviewEligibility } from "@/hooks/useQueries";
import type { ReviewEligibility } from "@/lib/services/reviews";

interface ReviewButtonProps {
  bookId: string;
  userId: string;
  /**
   * Initial review eligibility from SSR (prevents duplicate fetch, ensures correct button state on first load)
   */
  initialReviewEligibility?: ReviewEligibility;
}

/**
 * Entry point for submitting a book review.
 */
export default function ReviewButton({
  bookId,
  userId: _userId,
  initialReviewEligibility,
}: ReviewButtonProps) {
  const [showDialog, setShowDialog] = useState(false);

  // Use React Query hook for eligibility check with SSR initial data
  const { data: eligibility, isLoading } = useReviewEligibility(
    bookId,
    initialReviewEligibility,
  );

  const canReview = eligibility?.canReview || false;
  const hasExistingReview = eligibility?.hasExistingReview || false;
  const isCurrentlyBorrowed = eligibility?.isCurrentlyBorrowed || false;

  const handleReviewSubmitted = () => {
    setShowDialog(false);
    // CRITICAL: No manual invalidation needed here
    // The useCreateReview mutation in ReviewFormDialog already handles
    // all cache invalidation via invalidateAfterReviewChange()
    // which invalidates reviews, books, and analytics queries
    // Manual invalidation here would cause redundant refetches
  };

  if (isLoading) {
    return (
      <Button
        disabled
        className="flex items-center gap-1.5 border-[var(--mundia-line)] bg-[var(--surface-0)] text-slate-500 sm:gap-2"
      >
        <MessageCircle className="size-4 text-slate-500 sm:size-5" />
        <span className="text-sm font-semibold text-slate-500">Loading...</span>
      </Button>
    );
  }

  if (hasExistingReview) {
    return (
      <Button
        disabled
        className="mt-3 min-h-12 w-full bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)] sm:mt-4 sm:w-fit"
      >
        <MessageCircle className="size-4 text-white sm:size-5" />
        <span className="text-sm font-semibold text-white">
          Review submitted
        </span>
      </Button>
    );
  }

  if (!canReview) {
    return (
      <Button
        disabled
        className="mt-3 min-h-12 w-full bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)] sm:mt-4 sm:w-fit"
      >
        <MessageCircle className="size-4 text-white sm:size-5" />
        <span className="text-sm font-semibold text-white">
          {isCurrentlyBorrowed
            ? "Return borrowed book to review"
            : "Borrow Book to Review"}
        </span>
      </Button>
    );
  }

  return (
    <>
      <Button
        onClick={() => setShowDialog(true)}
        className="mt-3 min-h-12 w-full bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)] sm:mt-4 sm:w-fit"
      >
        <MessageCircle className="size-4 text-white sm:size-5" />
        <span className="text-sm font-semibold text-white">
          Review this book
        </span>
      </Button>

      <ReviewFormDialog
        bookId={bookId}
        isOpen={showDialog}
        onClose={() => setShowDialog(false)}
        onReviewSubmitted={handleReviewSubmitted}
      />
    </>
  );
}
