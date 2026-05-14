import React from "react";
import BookCard from "@/components/BookCard";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface Props {
  title: string;
  books: Book[];
  containerClassName?: string;
  showViewAllButton?: boolean;
}

/**
 * Sectioned list of book cards with optional CTA.
 */
const BookList = ({
  title,
  books,
  containerClassName,
  showViewAllButton = false,
}: Props) => {
  return (
    <section className={cn("fade-in-up", containerClassName)}>
      <div className="book-section-heading">
        <div>
          <p className="book-section-kicker">Curated Shelf</p>
          <h2 className="book-section-title">{title}</h2>
        </div>
        {showViewAllButton && books.length > 0 && (
          <Link href="/all-books" className="hidden sm:block">
            <Button className="book-section-cta">View All Books</Button>
          </Link>
        )}
      </div>

      {books.length > 0 ? (
        <ul className="book-list">
          {books.map((book) => (
            <BookCard key={book.title} {...book} isLoanedBook={false} />
          ))}
        </ul>
      ) : (
        <div className="mt-6 rounded-xl border border-[var(--mundia-line)] bg-[var(--surface-card)] p-6 text-center">
          <p className="text-base text-slate-600 sm:text-lg">
            No books available.
          </p>
        </div>
      )}

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
