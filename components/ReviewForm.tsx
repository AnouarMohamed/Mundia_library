"use client";

/**
 * ReviewForm Component
 *
 * Form component for submitting book reviews. Uses React Query mutation.
 * Integrates with useCreateReview mutation for proper cache invalidation.
 *
 * Features:
 * - Uses useCreateReview mutation
 * - Automatic cache invalidation on success
 * - Toast notifications via mutation callbacks
 * - Form validation
 */

import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { useCreateReview } from "@/hooks/useMutations";
import { Star } from "lucide-react";

interface ReviewFormProps {
  bookId: string;
  onReviewSubmitted: () => void;
  onCancel: () => void;
}

/**
 * Standalone review form for book pages.
 */
export default function ReviewForm({
  bookId,
  onReviewSubmitted,
  onCancel,
}: ReviewFormProps) {
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState("");

  // Use React Query mutation for creating review
  const createReviewMutation = useCreateReview();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!comment.trim()) {
      return; // Validation handled by mutation
    }

    // Use mutation to create review
    createReviewMutation.mutate(
      {
        bookId,
        rating,
        comment: comment.trim(),
      },
      {
        onSuccess: () => {
          onReviewSubmitted();
        },
      },
    );
  };

  const StarRating = () => (
    <div className="flex items-center gap-0.5 sm:space-x-1">
      {[1, 2, 3, 4, 5].map((star) => (
        <button
          key={star}
          type="button"
          onClick={() => setRating(star)}
          aria-label={`Set rating to ${star} star${star === 1 ? "" : "s"}`}
          className="focus-ring rounded p-1 transition-colors hover:text-[var(--mundia-gold)]"
        >
          <Star
            className={`size-5 sm:size-6 ${
              star <= rating
                ? "fill-[var(--mundia-gold)] text-[var(--mundia-gold)]"
                : "fill-light-100/20 text-light-100/20"
            }`}
          />
        </button>
      ))}
      <span className="ml-1.5 text-xs text-light-200/70 sm:ml-2 sm:text-sm">
        {rating} star{rating !== 1 ? "s" : ""}
      </span>
    </div>
  );

  return (
    <div className="surface-panel p-4 shadow-sm sm:p-6">
      <h3 className="mb-3 text-base font-semibold text-light-100 sm:mb-4 sm:text-lg">
        Write a review
      </h3>

      <form onSubmit={handleSubmit} className="space-y-3 sm:space-y-4">
        <div>
          <label className="mb-1.5 block text-xs font-medium text-light-200 sm:mb-2 sm:text-sm">
            Rating
          </label>
          <StarRating />
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-medium text-light-200 sm:mb-2 sm:text-sm">
            Your Review
          </label>
          <textarea
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="Share your thoughts about this book..."
            className="app-control min-h-28 w-full resize-none py-3"
            rows={4}
            required
          />
          <p className="mt-1 text-[10px] text-light-200/70 sm:text-xs">
            {comment.length}/500 characters
          </p>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end sm:space-x-3 sm:gap-0">
          <Button
            type="button"
            variant="outline"
            onClick={onCancel}
            disabled={createReviewMutation.isPending}
            className="w-full border-white/15 bg-white/5 text-xs text-light-100 hover:bg-white/10 hover:text-light-100 sm:w-auto sm:text-sm"
          >
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={createReviewMutation.isPending || !comment.trim()}
            className="w-full bg-primary text-xs text-dark-100 hover:bg-primary/90 sm:w-auto sm:text-sm"
          >
            {createReviewMutation.isPending ? "Submitting..." : "Submit review"}
          </Button>
        </div>
      </form>
    </div>
  );
}
