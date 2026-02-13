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

const BookList = ({
  title,
  books,
  containerClassName,
  showViewAllButton = false,
}: Props) => {
  return (
    <section className={cn("fade-in-up", containerClassName)}>
      <div className="mb-2">
        <p className="text-[11px] uppercase tracking-[0.26em] text-light-200/70 sm:text-xs">
          Curated Shelf
        </p>
        <h2 className="font-bebas-neue text-3xl tracking-[0.08em] text-light-100 sm:text-5xl">
          {title}
        </h2>
      </div>

      {books.length > 0 ? (
        <ul className="book-list">
          {books.map((book) => (
            <BookCard key={book.title} {...book} isLoanedBook={false} />
          ))}
        </ul>
      ) : (
        <div className="mt-6 rounded-2xl border border-white/10 bg-white/5 p-6 text-center">
          <p className="text-base text-light-100/85 sm:text-lg">No books available.</p>
        </div>
      )}

      {showViewAllButton && (
        <div className="mt-6 flex justify-center sm:mt-12">
          <Link href="/all-books">
            <Button className="min-h-12 rounded-xl border border-primary/50 bg-primary px-6 py-3 font-bebas-neue text-base tracking-[0.08em] text-dark-100 hover:bg-primary/95 sm:text-xl">
              View All Books
            </Button>
          </Link>
        </div>
      )}
    </section>
  );
};
export default BookList;
