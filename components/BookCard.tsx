import React from "react";
import Link from "next/link";
import BookCover from "@/components/BookCover";
import { cn } from "@/lib/utils";
// import Image from "next/image";
import { Button } from "@/components/ui/button";

/**
 * Properties for the BookCard component.
 * Extends the base Book type and adds UI-specific flags.
 */
interface BookCardProps extends Book {
  /** 
   * If true, renders additional information relevant to a borrowed book 
   * (e.g., return countdown, receipt download). 
   */
  isLoanedBook?: boolean;
}

/**
 * A compact, visually rich card representing a book in the library.
 * 
 * Features:
 * - Displays book cover using the `BookCover` component.
 * - Shows genre, rating, title, and author.
 * - Conditionally displays availability stats if provided.
 * - Supports an "isLoanedBook" mode for the student's personal profile view.
 */
const BookCard = ({
  id,
  title,
  author,
  genre,
  rating,
  availableCopies,
  totalCopies,
  coverColor,
  coverUrl,
  isLoanedBook = false,
}: BookCardProps) => {
  // Safe formatting for the star rating
  const formattedRating =
    typeof rating === "number" ? rating.toFixed(1) : String(rating ?? "N/A");
  
  // Logic to determine if inventory details should be shown
  const hasCopyData =
    typeof availableCopies === "number" && typeof totalCopies === "number";

  return (
    <li className={cn("group", isLoanedBook && "xs:w-52 w-full")}>
      <Link
        href={`/books/${id}`}
        className={cn(
          "book-card-link",
          isLoanedBook && "flex w-full flex-col items-center",
        )}
      >
        {/* Visual Cover Section */}
        <div className="book-card-cover">
          <BookCover coverColor={coverColor} coverImage={coverUrl} />
        </div>

        {/* Metadata Section */}
        <div
          className={cn(
            "mt-3 sm:mt-4",
            !isLoanedBook && "xs:max-w-44 max-w-32",
          )}
        >
          <div className="mb-2 flex items-center justify-between gap-2">
            <p className="line-clamp-1 text-[10px] uppercase tracking-[0.18em] text-light-200/85 sm:text-xs">
              {genre}
            </p>
            <span className="book-card-rating">
              <img
                src="/icons/star.svg"
                alt="star"
                width={14}
                height={14}
                className="size-3.5"
              />
              {formattedRating}
            </span>
          </div>
          <p className="line-clamp-2 text-sm font-semibold leading-snug text-light-100 transition-colors group-hover:text-white sm:text-base">
            {title}
          </p>
          <p className="mt-1.5 line-clamp-1 text-xs text-light-100/70 sm:text-sm">
            {author}
          </p>
          
          {/* Inventory Stats (Hidden on loaned books to save space) */}
          {hasCopyData && (
            <p className="book-card-copy mt-3">
              {availableCopies} of {totalCopies} available
            </p>
          )}
        </div>

        {/* Loaned Book Specific Actions */}
        {isLoanedBook && (
          <div className="mt-2.5 w-full sm:mt-3">
            <div className="book-loaned rounded-lg border border-white/10 bg-white/5 px-2 py-1.5">
              <img
                src="/icons/calendar.svg"
                alt="calendar"
                width={18}
                height={18}
                className="size-4 object-contain sm:size-[18px]"
              />
              <p className="text-xs text-light-100 sm:text-sm">
                11 days left to return
              </p>
            </div>

            <Button className="book-btn mt-2.5">Download receipt</Button>
          </div>
        )}
      </Link>
    </li>
  );
};

export default BookCard;
