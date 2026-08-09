import BookVideo from "@/components/BookVideo";
import ReviewsSection from "@/components/ReviewsSection";

interface BookDetailContentProps {
  bookId: string;
  initialBook: Book;
  initialReviews: Array<{
    id: string;
    rating: number;
    comment: string;
    createdAt: Date | null;
    updatedAt: Date | null;
    userFullName: string;
    isOwner: boolean;
  }>;
}

const BookDetailContent = ({
  bookId,
  initialBook,
  initialReviews,
}: BookDetailContentProps) => (
  <div className="book-details">
    <div className="w-full min-w-0 max-w-full overflow-hidden">
      <section>
        <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">Summary</h2>
        <div className="mt-3 space-y-3 break-words text-sm leading-7 text-[var(--mundia-muted)] sm:text-base">
          {initialBook.summary?.split("\n").map((line) => (
            <p key={line}>{line}</p>
          ))}
        </div>
      </section>

      {initialBook.videoUrl && (
        <details className="mt-7 border-t border-[var(--mundia-line)] pt-1">
          <summary className="flex min-h-12 cursor-pointer list-none items-center justify-between text-sm font-semibold text-[var(--mundia-ink)] marker:hidden">
            Watch book introduction
            <span aria-hidden="true">+</span>
          </summary>
          <div className="pb-4 pt-2">
            <BookVideo videoUrl={initialBook.videoUrl} />
          </div>
        </details>
      )}

      <section className="mt-7 border-t border-[var(--mundia-line)] pt-6">
        <ReviewsSection bookId={bookId} reviews={initialReviews} />
      </section>
    </div>
  </div>
);

export default BookDetailContent;
