/**
 * BookCardSkeleton Component
 * 
 * Provides a structural loading placeholder that mirrors the BookCard component.
 * Ensures the library grid remains stable while actual book metadata is loaded.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

import React from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/**
 * Props for BookCardSkeleton
 */
interface BookCardSkeletonProps {
  /**
   * If true, renders additional elements present in the 'Loaned Book' view 
   * (e.g., date badges and action buttons).
   * @default false
   */
  isLoanedBook?: boolean;
  /**
   * Additional CSS classes for external layout control
   */
  className?: string;
}

/**
 * BookCardSkeleton
 * 
 * A skeleton loader that matches the exact dimensions and layout of the BookCard component.
 * 
 * Dimensions matched:
 * - BookCover: xs:w-[174px] w-[114px] xs:h-[239px] h-[169px]
 * - Title/Author/Genre text blocks
 */
const BookCardSkeleton: React.FC<BookCardSkeletonProps> = ({
  isLoanedBook = false,
  className,
}) => {
  return (
    <li className={cn(isLoanedBook && "xs:w-52 w-full", className)}>
      <div
        className={cn("flex flex-col", isLoanedBook && "w-full items-center")}
      >
        {/* Book Cover Placeholder - matches BookCover regular size precisely */}
        <Skeleton
          className={cn(
            "xs:w-[174px] w-[114px] xs:h-[239px] h-[169px]",
            "shrink-0"
          )}
        />

        {/* Text Content Block */}
        <div
          className={cn(
            "mt-4 flex flex-col",
            !isLoanedBook && "xs:max-w-40 max-w-28"
          )}
        >
          {/* Title Placeholder */}
          <Skeleton className="mt-2 h-5 w-full xs:h-6" />

          {/* Author Placeholder */}
          <Skeleton className="mt-1 h-4 w-3/4 xs:h-5" />

          {/* Genre Placeholder */}
          <Skeleton className="mt-1 h-4 w-2/3 xs:h-5" />
        </div>

        {/* Loaned Book Specific Elements (Badges & Buttons) */}
        {isLoanedBook && (
          <div className="mt-3 flex w-full flex-col gap-3">
            {/* Countdown Badge Placeholder */}
            <div className="flex flex-row items-center gap-1 max-xs:justify-center">
              <Skeleton className="size-[18px] shrink-0" />
              <Skeleton className="h-4 w-32" />
            </div>

            {/* Primary Action Button Placeholder */}
            <Skeleton className="min-h-14 w-full rounded-md" />
          </div>
        )}
      </div>
    </li>
  );
};

export default BookCardSkeleton;
