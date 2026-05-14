"use client";

/**
 * ReturnBookButton Component
 *
 * Button component for returning books. Uses React Query mutation.
 * Integrates with useReturnBook mutation for proper cache invalidation.
 *
 * Features:
 * - Uses useReturnBook mutation
 * - Automatic cache invalidation on success
 * - Toast notifications via mutation callbacks
 * - No page refresh needed - uses cache invalidation
 */

import React from "react";
import { Button } from "@/components/ui/button";
import { useReturnBook } from "@/hooks/useMutations";

interface Props {
  recordId: string;
  bookTitle: string;
  dueDate: Date | null; // Can be null for pending requests
}

/**
 * Return action with overdue styling.
 */
const ReturnBookButton = ({ recordId, bookTitle, dueDate }: Props) => {
  // Use React Query mutation for returning book
  const returnBookMutation = useReturnBook();

  const handleReturnBook = () => {
    // Use mutation to return book
    returnBookMutation.mutate(
      {
        recordId,
        bookTitle,
      },
      {
        onError: (error) => {
          console.error("[ReturnBookButton] Mutation error:", error);
        },
      },
    );
  };

  // Calculate if book is overdue (only if dueDate exists)
  const today = new Date();
  const isOverdue = dueDate && today > new Date(dueDate);
  const daysOverdue = isOverdue
    ? Math.floor(
        (today.getTime() - new Date(dueDate).getTime()) / (1000 * 60 * 60 * 24),
      )
    : 0;

  return (
    <Button
      className={`mt-0 min-h-12 w-full rounded-lg border text-white sm:w-fit ${
        isOverdue
          ? "border-red-300/40 bg-red-500 hover:bg-red-500/90"
          : "border-orange-300/40 bg-orange-500 hover:bg-orange-500/90"
      }`}
      onClick={handleReturnBook}
      disabled={returnBookMutation.isPending}
    >
      <img
        src="/icons/book.svg"
        alt="return book"
        width={20}
        height={20}
        className="size-4 sm:size-5"
      />
      <span className="text-sm font-semibold text-white">
        {returnBookMutation.isPending
          ? "Returning..."
          : isOverdue
            ? `Return Book (${daysOverdue} days overdue)`
            : "Return Book"}
      </span>
    </Button>
  );
};

export default ReturnBookButton;
