/**
 * ReviewButton Component
 * 
 * Handles the entry point for book reviews. 
 * Orchestrates eligibility checks and triggers the ReviewFormDialog.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import ReviewFormDialog from "@/components/ReviewFormDialog";
import { MessageCircle } from "lucide-react";
import { useReviewEligibility } from "@/hooks/useQueries";
import type { ReviewEligibility } from "@/lib/services/reviews";

/**
 * Props for ReviewButton
 */
interface ReviewButtonProps {
  /**
   * Unique identifier of the book (UUID)
   */
  bookId: string;
  /**
   * Unique identifier of the user (UUID)
   */
  userId: string;
  /**
   * Initial review eligibility from SSR (prevents duplicate fetch)
   */
  initialReviewEligibility?: ReviewEligibility;
}

/**
 * ReviewButton
 * 
 * Entry point for submitting a book review.
 * Features:
 * - Uses useReviewEligibility hook for eligibility check
 * - Shows loading state while checking eligibility
 * - Displays appropriate button state based on eligibility
 * - Opens ReviewFormDialog when eligible
 */
export default function ReviewButton({
  bookId,
  userId: _userId,
  initialReviewEligibility,
}: ReviewButtonProps) {
  const [showDialog, setShowDialog] = useState(false);

  // Fetch live eligibility state using React Query, leveraging SSR data for hydration
  const { data: eligibility, isLoading } = useReviewEligibility(
    bookId,
    initialReviewEligibility,
  );

  // Derived state for better readability
  const canReview = eligibility?.canReview || false;
  const hasExistingReview = eligibility?.hasExistingReview || false;
  const isCurrentlyBorrowed = eligibility?.isCurrentlyBorrowed || false;

  /**
   * SUCCESS CALLBACK:
   * Invoked after the ReviewFormDialog successfully submits a review.
   */
  const handleReviewSubmitted = () => {
    setShowDialog(false);
    /**
     * CRITICAL PERFORMANCE NOTE: 
     * Cache invalidation is handled centrally within the mutation success callback
     * in ReviewFormDialog. Manual invalidation here would be redundant.
     */
  };

  // State 1: Still verifying eligibility
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

  // State 2: User has already reviewed this book
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

  // State 3: User is not yet allowed to review (e.g., must return book first)
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

  // State 4: User is eligible to review
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

      {/* The actual modal form for data entry */}
      <ReviewFormDialog
        bookId={bookId}
        isOpen={showDialog}
        onClose={() => setShowDialog(false)}
        onReviewSubmitted={handleReviewSubmitted}
      />
    </>
  );
}
