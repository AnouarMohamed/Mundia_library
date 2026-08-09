/**
 * ReturnBookButton Component
 * 
 * A specialized button that initiates the book return workflow.
 * Features real-time state updates via TanStack Query and visual 
 * indicators for overdue status.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import { Button } from "@/components/ui/button";
import { useReturnBook } from "@/hooks/useMutations";
import { useRouter } from "next/navigation";

/**
 * Props for ReturnBookButton
 */
interface Props {
  /**
   * The unique ID of the borrow record being closed
   */
  recordId: string;
  /**
   * Display title of the book
   */
  bookTitle: string;
  /**
   * Deadline date for returning the book
   */
  dueDate: Date | null; 
}

/**
 * ReturnBookButton
 * 
 * Button component for returning books. Uses React Query mutation.
 * Integrates with useReturnBook mutation for proper cache invalidation.
 */
const ReturnBookButton = ({ recordId, bookTitle, dueDate }: Props) => {
  const router = useRouter();
  // Initialize the return book mutation hook
  const returnBookMutation = useReturnBook();

  /**
   * Handler for the return action.
   * Executes the mutation which handles server persistence and cache cleanup.
   */
  const handleReturnBook = () => {
    returnBookMutation.mutate(
      {
        recordId,
        bookTitle,
      },
      {
        onSuccess: () => router.refresh(),
        onError: (error) => {
          console.error("[ReturnBookButton] Mutation error:", error);
        },
      },
    );
  };

  /**
   * Urgency/Status Logic:
   * Determines if the book is currently overdue and calculates the day count.
   */
  const today = new Date();
  const isOverdue = dueDate && today > new Date(dueDate);
  const daysOverdue = isOverdue
    ? Math.floor(
        (today.getTime() - new Date(dueDate).getTime()) / (1000 * 60 * 60 * 24),
      )
    : 0;

  return (
    <Button
      className="mt-0 min-h-12 w-full rounded-lg border border-[var(--mundia-navy)] bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)] sm:w-fit"
      onClick={handleReturnBook}
      disabled={returnBookMutation.isPending}
    >
      <img
        src="/icons/book.svg"
        alt=""
        aria-hidden="true"
        width={20}
        height={20}
        className="size-4 sm:size-5"
      />
      
      {/* Dynamic text based on current state (Pending, Overdue, or Normal) */}
      <span className="text-sm font-semibold text-white">
        {returnBookMutation.isPending
          ? "Returning..."
          : isOverdue
            ? `Return book · ${daysOverdue} days overdue`
            : "Return book"}
      </span>
    </Button>
  );
};

export default ReturnBookButton;
