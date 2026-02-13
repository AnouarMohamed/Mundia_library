import React from "react";
import Link from "next/link";
import BookCover from "@/components/BookCover";
import { cn } from "@/lib/utils";
// import Image from "next/image";
import { Button } from "@/components/ui/button";

interface BookCardProps extends Book {
  isLoanedBook?: boolean;
}

const BookCard = ({
  id,
  title,
  author,
  genre,
  coverColor,
  coverUrl,
  isLoanedBook = false,
}: BookCardProps) => (
  <li className={cn("group", isLoanedBook && "xs:w-52 w-full")}>
    <Link
      href={`/books/${id}`}
      className={cn(
        "block rounded-2xl border border-white/10 bg-[rgba(8,14,22,0.58)] p-3 transition duration-300 hover:-translate-y-1 hover:border-primary/65 hover:bg-[rgba(10,19,29,0.78)] sm:p-4",
        isLoanedBook && "flex w-full flex-col items-center"
      )}
    >
      <div className="flex justify-center">
        <BookCover coverColor={coverColor} coverImage={coverUrl} />
      </div>

      <div className={cn("mt-3 sm:mt-4", !isLoanedBook && "xs:max-w-40 max-w-28")}>
        <p className="line-clamp-2 text-sm font-semibold text-light-100 transition-colors group-hover:text-primary sm:text-base">
          {title}
        </p>
        <p className="mt-1.5 line-clamp-1 text-xs text-light-100/75 sm:text-sm">{author}</p>
        <p className="mt-1 line-clamp-1 text-[10px] uppercase tracking-[0.18em] text-light-200/80 sm:text-xs">
          {genre}
        </p>
      </div>

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
            <p className="text-xs text-light-100 sm:text-sm">11 days left to return</p>
          </div>

          <Button className="book-btn mt-2.5">Download receipt</Button>
        </div>
      )}
    </Link>
  </li>
);

export default BookCard;
