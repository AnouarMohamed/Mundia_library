/**
 * ReviewFormDialog Component
 * 
 * A modal dialog that wraps the book review form. 
 * Provides a focused user experience for providing feedback and ratings.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Star } from "lucide-react";
import { useCreateReview } from "@/hooks/useMutations";

/**
 * Props for ReviewFormDialog
 */
interface ReviewFormDialogProps {
  /**
   * Unique ID of the book being reviewed
   */
  bookId: string;
  /**
   * Visibility state of the dialog
   */
  isOpen: boolean;
  /**
   * Callback function to close the dialog
   */
  onClose: () => void;
  /**
   * Callback triggered after a successful review submission
   */
  onReviewSubmitted: () => void;
}

/**
 * ReviewFormDialog
 * 
 * Modal review form with submission feedback.
 * Features:
 * - Uses useCreateReview mutation for data persistence.
 * - Managed local state for ratings and comments.
 * - Automatic reset and closure upon success.
 */
export default function ReviewFormDialog({
  bookId,
  isOpen,
  onClose,
  onReviewSubmitted,
}: ReviewFormDialogProps) {
  // Local form state
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState("");
  const commentId = React.useId();

  // Initialize review mutation
  const createReviewMutation = useCreateReview();

  /**
   * Handles the submission of the review.
   */
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!comment.trim()) {
      return; 
    }

    // Execute the mutation with lifecycle callbacks
    createReviewMutation.mutate(
      {
        bookId,
        rating,
        comment: comment.trim(),
      },
      {
        onSuccess: () => {
          /**
           * DELAYED CLOSURE:
           * We add a short delay to allow the user to see the success toast 
           * notification before the modal disappears.
           */
          setTimeout(() => {
            onReviewSubmitted();
            onClose();
            // Reset local form state for future use
            setRating(5);
            setComment("");
          }, 1500);
        },
      },
    );
  };

  /**
   * Safely handles dialog closure.
   * Prevents closing while a submission is actively pending.
   */
  const handleClose = () => {
    if (!createReviewMutation.isPending) {
      onClose();
      // Reset form state on close to ensure a clean slate next time
      setRating(5);
      setComment("");
    }
  };

  /**
   * Internal StarRating sub-component for visual rating selection.
   */
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
                : "fill-slate-200 text-slate-200"
            }`}
          />
        </button>
      ))}
      <span className="ml-1.5 text-xs text-slate-600 sm:ml-2 sm:text-sm">
        {rating} star{rating !== 1 ? "s" : ""}
      </span>
    </div>
  );

  return (
    <Dialog open={isOpen} onOpenChange={handleClose}>
      <DialogContent className="surface-panel sm:max-w-md [&>button]:text-[var(--mundia-ink)] [&>button]:hover:text-[var(--mundia-ink)]">
        {/* Header Section */}
        <DialogHeader>
          <DialogTitle className="text-base text-[var(--mundia-ink)] sm:text-lg">
            Write a review
          </DialogTitle>
          <DialogDescription className="text-xs text-slate-600 sm:text-sm">
            Rate the book and leave a short note for other readers.
          </DialogDescription>
        </DialogHeader>

        {/* Form Section */}
        <form onSubmit={handleSubmit} className="space-y-4 sm:space-y-6">
          {/* Rating Selection */}
          <fieldset className="space-y-1.5 sm:space-y-2">
            <legend className="text-xs font-medium text-slate-600 sm:text-sm">
              Rating
            </legend>
            <StarRating />
          </fieldset>

          {/* Comment Area */}
          <div className="space-y-1.5 sm:space-y-2">
            <label
              htmlFor={commentId}
              className="text-xs font-medium text-slate-600 sm:text-sm"
            >
              Your Review
            </label>
            <textarea
              id={commentId}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder="Share your thoughts about this book..."
              className="app-control min-h-28 w-full resize-none py-3"
              rows={4}
              required
            />
            <p className="text-[10px] text-slate-500 sm:text-xs">
              {comment.length}/500 characters
            </p>
          </div>

          {/* Footer with actions */}
          <DialogFooter className="flex-col gap-2 sm:flex-row sm:gap-0">
            <Button
              type="button"
              variant="outline"
              onClick={handleClose}
              disabled={createReviewMutation.isPending}
              className="w-full border-[var(--mundia-line)] bg-[var(--mundia-paper)] text-xs text-[var(--mundia-ink)] hover:bg-[var(--mundia-panel)] sm:w-auto sm:text-sm"
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={createReviewMutation.isPending || !comment.trim()}
              className="w-full bg-[var(--mundia-navy)] text-xs text-white hover:bg-[var(--mundia-navy-strong)] sm:w-auto sm:text-sm"
            >
              {createReviewMutation.isPending
                ? "Submitting..."
                : "Submit review"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
