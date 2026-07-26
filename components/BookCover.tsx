/**
 * BookCover Component
 *
 * An optimized component for rendering book covers with a consistent look.
 * Includes a custom SVG spine and handles various image sources (External, ImageKit, Data-URI).
 *
 * @author Mundia Library Team
 * @version 1.2.0
 */

"use client";

import React from "react";
import { Image as IKImage } from "@imagekit/next";
import { cn } from "@/lib/utils";
import BookCoverSvg from "@/components/BookCoverSvg";
import config from "@/lib/config";

/**
 * Available size variants for the book cover
 */
type BookCoverVariant = "extraSmall" | "small" | "medium" | "regular" | "wide";

/**
 * CSS classes mapping for different variants
 */
const variantStyles: Record<BookCoverVariant, string> = {
  extraSmall: "book-cover_extra_small",
  small: "book-cover_small",
  medium: "book-cover_medium",
  regular: "book-cover_regular",
  wide: "book-cover_wide",
};

/**
 * Props for BookCover
 */
interface Props {
  /**
   * Additional CSS classes for the container
   */
  className?: string;
  /**
   * Size variant of the cover
   * @default "regular"
   */
  variant?: BookCoverVariant;
  /**
   * Background color for the book spine and fallback
   */
  coverColor: string;
  /**
   * URL or path for the cover image
   */
  coverImage: string;
}

/**
 * BookCover
 *
 * Optimized component to prevent image flicker during React Query refetches.
 *
 * CRITICAL PERFORMANCE NOTE:
 * Uses React.memo with custom comparison to prevent unnecessary re-renders.
 * Only re-renders when coverImage or coverColor actually changes.
 * The image will only reload if the coverImage URL actually changes,
 * not when the component re-renders due to query refetch in parent components.
 */
const BookCover = React.memo(
  ({ className, variant = "regular", coverColor, coverImage }: Props) => {
    return (
      <div
        className={cn(
          "relative transition-all duration-300",
          variantStyles[variant],
          className,
        )}
      >
        {/* Render the background SVG spine with the specified theme color */}
        <BookCoverSvg coverColor={coverColor} />

        {/* Content container for the actual book cover image */}
        <div
          className="absolute z-10"
          style={{ left: "12%", width: "87.5%", height: "88%" }}
        >
          {/* 
            Strategy 1: Direct URL (External or Data-URI)
            Optimized for fast loading and async decoding.
          */}
          {coverImage &&
          (coverImage.startsWith("http") || coverImage.startsWith("data:")) ? (
            <img
              // CRITICAL: Removed key prop - it causes remounts and flickering
              src={coverImage}
              alt="Book cover"
              className="size-full rounded-sm object-fill"
              loading="eager"
              decoding="async"
              fetchPriority="high"
            />
          ) : /* 
            Strategy 2: ImageKit Path
            Uses specialized IKImage for responsive transformations and placeholders.
          */
          coverImage && config.env.imagekit.urlEndpoint ? (
            <IKImage
              // CRITICAL: Removed key prop - it causes remounts and flickering
              src={coverImage}
              urlEndpoint={config.env.imagekit.urlEndpoint}
              alt="Book cover"
              fill
              className="rounded-sm object-fill"
            />
          ) : (
            /* 
              Fallback Strategy: Rendered when no image is provided or loading fails.
            */
            <div className="flex size-full items-center justify-center rounded-sm bg-gray-200">
              <span className="text-xs text-gray-500 sm:text-sm">No Cover</span>
            </div>
          )}
        </div>
      </div>
    );
  },
  (prevProps, nextProps) => {
    /**
     * Custom Comparison Logic:
     * Only re-render if the core visual properties change.
     * This prevents flicker when parent component re-renders but image data is identical.
     */
    return (
      prevProps.coverImage === nextProps.coverImage &&
      prevProps.coverColor === nextProps.coverColor &&
      prevProps.variant === nextProps.variant &&
      prevProps.className === nextProps.className
    );
  },
);

// Set display name for React DevTools debugging
BookCover.displayName = "BookCover";

export default BookCover;
