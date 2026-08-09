import Link from "next/link";
import { AlertCircle, BookOpen, Star } from "lucide-react";
import BookBorrowButton from "@/components/BookBorrowButton";
import BookBorrowStats from "@/components/BookBorrowStats";
import BookCover from "@/components/BookCover";
import { Button } from "@/components/ui/button";
import type { BorrowRecord } from "@/lib/services/borrows";
import type { ReviewEligibility } from "@/lib/services/reviews";

interface BookOverviewContentProps {
  userId: string;
  userStatus?: string | null;
  isDetailPage?: boolean;
  initialBook: Book;
  initialStats?: {
    totalBorrows: number;
    activeBorrows: number;
    returnedBorrows: number;
  };
  initialUserBorrows?: BorrowRecord[];
  initialReviewEligibility?: ReviewEligibility;
}

const BookOverviewContent = ({
  userId,
  userStatus,
  isDetailPage = false,
  initialBook: book,
  initialStats,
  initialUserBorrows,
  initialReviewEligibility,
}: BookOverviewContentProps) => {
  const {
    title,
    author,
    genre,
    rating,
    totalCopies,
    availableCopies,
    description,
    coverColor,
    coverUrl,
    id,
    isbn,
    publicationYear,
    publisher,
    language,
    pageCount,
    edition,
    isActive,
  } = book;
  const isAvailable = isActive && availableCopies > 0;
  const formattedRating =
    typeof rating === "number" ? rating.toFixed(1) : String(rating ?? "N/A");
  const detailFields = [
    { label: "Published", value: publicationYear ?? "Not listed" },
    { label: "Publisher", value: publisher || "Not listed" },
    { label: "Language", value: language || "Not listed" },
    { label: "Pages", value: pageCount ?? "Not listed" },
    { label: "Edition", value: edition || "Not listed" },
    { label: "ISBN", value: isbn || "Not listed" },
  ];

  return (
    <section className="book-overview">
      <div className="relative z-10 min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-[var(--mundia-muted)]">
            {genre}
          </span>
          {!isActive && (
            <span className="inline-flex items-center gap-1 text-sm font-semibold text-[var(--mundia-danger)]">
              <AlertCircle className="size-4" aria-hidden="true" />
              Catalog listing inactive
            </span>
          )}
        </div>

        <h1 className="mt-2 break-words">{title}</h1>
        <p className="mt-2 text-sm text-[var(--mundia-muted)] sm:text-base">
          By <span className="font-semibold text-[var(--mundia-ink)]">{author}</span>
        </p>

        <div className="mt-5 grid grid-cols-[6rem_minmax(0,1fr)] gap-4 sm:grid-cols-[8rem_minmax(0,1fr)] xl:block">
          <div className="flex items-start justify-center rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] p-2 xl:hidden">
            <BookCover
              variant="medium"
              className="h-[8.3rem] w-24 sm:h-[11rem] sm:w-32"
              coverColor={coverColor}
              coverImage={coverUrl}
              title={title}
              priority
            />
          </div>

          <div className="min-w-0">
            <p
              className={`text-lg font-semibold ${
                isAvailable
                  ? "text-[var(--mundia-success-strong)]"
                  : "text-[var(--mundia-danger)]"
              }`}
            >
              {isAvailable ? "Available now" : "Currently unavailable"}
            </p>
            <p className="mt-1 text-sm leading-5 text-[var(--mundia-muted)]">
              {availableCopies} of {totalCopies} copies ready to borrow
            </p>
            <p className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-[var(--mundia-ink)]">
              <Star
                className="size-4 fill-[var(--mundia-gold)] text-[var(--mundia-gold)]"
                aria-hidden="true"
              />
              {formattedRating} reader rating
            </p>
          </div>
        </div>

        {userId && (
          <div className="mt-5 flex flex-col gap-2 sm:flex-row sm:items-center">
            <BookBorrowButton
              bookId={id}
              userId={userId}
              bookTitle={title}
              availableCopies={availableCopies}
              isActive={isActive}
              userStatus={userStatus}
              isDetailPage={isDetailPage}
              initialUserBorrows={initialUserBorrows}
              initialReviewEligibility={initialReviewEligibility}
            />
            {!isDetailPage && (
              <Button
                asChild
                variant="outline"
                className="min-h-12 w-full rounded-lg border-[var(--mundia-line)] bg-transparent text-[var(--mundia-ink)] hover:bg-[var(--mundia-panel)] sm:w-fit"
              >
                <Link href={`/books/${id}`}>
                  <BookOpen className="size-4" aria-hidden="true" />
                  Book details
                </Link>
              </Button>
            )}
          </div>
        )}

        <div className="mt-6 border-t border-[var(--mundia-line)] pt-5">
          <h2 className="font-serif text-xl text-[var(--mundia-ink)]">About this book</h2>
          <p className="mt-2 text-sm leading-6 text-[var(--mundia-muted)] sm:text-base sm:leading-7">
            {description}
          </p>
        </div>

        <details className="mt-5 border-t border-[var(--mundia-line)] pt-1">
          <summary className="flex min-h-12 cursor-pointer list-none items-center justify-between text-sm font-semibold text-[var(--mundia-ink)] marker:hidden">
            Publication details
            <span aria-hidden="true">+</span>
          </summary>
          <dl className="grid gap-x-8 gap-y-3 pb-4 text-sm sm:grid-cols-2">
            {detailFields.map((item) => (
              <div key={item.label} className="flex justify-between gap-4">
                <dt className="text-[var(--mundia-muted)]">{item.label}</dt>
                <dd className="max-w-[14rem] truncate text-right font-medium text-[var(--mundia-ink)]">
                  {item.value}
                </dd>
              </div>
            ))}
          </dl>
        </details>

        {initialStats && (
          <details className="border-t border-[var(--mundia-line)] pt-1">
            <summary className="flex min-h-12 cursor-pointer list-none items-center justify-between text-sm font-semibold text-[var(--mundia-ink)] marker:hidden">
              Borrowing activity
              <span aria-hidden="true">+</span>
            </summary>
            <BookBorrowStats
              availableCopies={availableCopies}
              initialStats={initialStats}
            />
          </details>
        )}
      </div>

      <aside className="relative z-10 hidden flex-1 items-start justify-center xl:flex" aria-label="Book cover">
        <div className="rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] p-6">
          <BookCover
            variant="wide"
            className="z-10"
            coverColor={coverColor}
            coverImage={coverUrl}
            title={title}
            decorative
          />
        </div>
      </aside>
    </section>
  );
};

export default BookOverviewContent;
