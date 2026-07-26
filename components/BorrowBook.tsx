/**
 * BorrowBook Component
 * 
 * A specialized button component that handles the book borrowing workflow.
 * Integrates with React Query mutations for data persistence and state synchronization.
 * 
 * @author Mundia Library Team
 * @version 1.1.0
 */

"use client";

import React from "react";
import { Button } from "@/components/ui/button";
import { useRouter } from "next/navigation";
import { BookOpen } from "lucide-react";
import { useBorrowBook } from "@/hooks/useMutations";

/**
 * Props for BorrowBook
 */
interface Props {
  /**
   * Unique identifier of the user who wants to borrow the book
   */
  userId: string;
  /**
   * Unique identifier of the book to be borrowed
   */
  bookId: string;
  /**
   * Eligibility state determined by the server/parent component
   */
  borrowingEligibility: {
    /**
     * True if the user is allowed to borrow this book
     */
    isEligible: boolean;
    /**
     * Descriptive message for eligibility status
     */
    message: string;
  };
}

/**
 * BorrowBook
 * 
 * Button component for borrowing books. Uses React Query mutation.
 * Integrates with useBorrowBook mutation for proper cache invalidation.
 *
 * Features:
 * - Uses useBorrowBook mutation
 * - Automatic cache invalidation on success
 * - Toast notifications via mutation callbacks
 * - Navigation to profile page on success
 */
const BorrowBook = ({
  userId,
  bookId,
  borrowingEligibility: { isEligible },
}: Props) => {
  const router = useRouter();

  // Initialize the borrow book mutation hook
  const borrowBookMutation = useBorrowBook();

  /**
   * Handler for the borrow button click.
   * Triggers the mutation and handles lifecycle callbacks.
   */
  const handleBorrowBook = () => {
    // Secondary safety check: Eligibility should be handled by UI state (disabled button)
    // but we check here as well to prevent accidental execution.
    if (!isEligible) {
      return; 
    }

    // Execute the mutation
    borrowBookMutation.mutate(
      {
        userId,
        bookId,
      },
      {
        onSuccess: () => {
          /**
           * POST-SUCCESS NAVIGATION:
           * The optimistic update has already patched the cache.
           * We navigate the user to their profile to see their newly borrowed book.
           */
          router.push("/my-profile");
        },
        onError: (error) => {
          console.error("[BorrowBook] Mutation error:", error);
          // Fallback alert for critical errors if toast fails or is not present
          alert(`Failed to borrow book: ${error.message}`);
        },
      },
    );
  };

  return (
    <Button
      className="mt-0 min-h-12 w-full rounded-lg bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)] sm:w-fit"
      onClick={handleBorrowBook}
      disabled={borrowBookMutation.isPending || !isEligible}
    >
      <BookOpen className="size-4 text-white sm:size-5" />
      <span className="text-sm font-semibold text-white">
        {borrowBookMutation.isPending ? "Borrowing ..." : "Borrow Book"}
      </span>
    </Button>
  );
};

export default BorrowBook;
