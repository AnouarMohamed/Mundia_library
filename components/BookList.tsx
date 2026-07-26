/**
 * BookList Component
 * 
 * Renders a labeled section containing a list of book cards. 
 * Includes responsive layout handling and optional "View All" redirection.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

import React from "react";
import BookCard from "@/components/BookCard";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Props for BookList
 */
interface Props {
  /**
   * The heading title for the section
   */
  title: string;
  /**
   * Array of book objects to display
   */
  books: Book[];
  /**
   * Optional additional CSS classes for the container
   */
  containerClassName?: string;
  /**
   * Whether to show the "View All Books" button
   * @default false
   */
  showViewAllButton?: boolean;
}

/**
 * BookList
 * 
 * Sectioned list of book cards with optional call-to-action (CTA).
 * 
 * @param {Props} props - Component properties
 * @returns {JSX.Element} The rendered book list section
 */
const BookList = ({
  title,
  books,
  containerClassName,
  showViewAllButton = false,
}: Props) => {
  return (
    <section className={cn("fade-in-up", containerClassName)}>
      {/* Section Header: Title and optional CTA */}
      <div className="book-section-heading">
        <div>
          <p className="book-section-kicker">Curated Shelf</p>
          <h2 className="book-section-title">{title}</h2>
        </div>
        
        {/* Desktop View All Button */}
        {showViewAllButton && books.length > 0 && (
          <Link href="/all-books" className="hidden sm:block">
            <Button className="book-section-cta">View All Books</Button>
          </Link>
        )}
      </div>

      {/* Book Grid/List - Conditional rendering for empty state */}
      {books.length > 0 ? (
        <ul className="book-list">
          {books.map((book) => (
            <BookCard key={book.title} {...book} isLoanedBook={false} />
          ))}
        </ul>
      ) : (
        <div className="mt-6 rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card)] p-6 text-center">
          <p className="text-base text-slate-600 sm:text-lg">
            No books available.
          </p>
        </div>
      )}

      {/* Mobile View All Button - Placed at the bottom for better UX on small screens */}
      {showViewAllButton && (
        <div className="mt-6 flex justify-center sm:hidden">
          <Link href="/all-books">
            <Button className="book-section-cta">View All Books</Button>
          </Link>
        </div>
      )}
    </section>
  );
};

export default BookList;
