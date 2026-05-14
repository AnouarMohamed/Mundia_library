import Link from "next/link";
import { redirect } from "next/navigation";
import { and, desc, eq } from "drizzle-orm";
import { unstable_cache } from "next/cache";
import { db } from "@/database/drizzle";
import { books, borrowRecords } from "@/database/schema";
import BookOverview from "@/components/BookOverview";
import HomeRecommendations from "@/components/HomeRecommendations";
import { Button } from "@/components/ui/button";
import { getSession } from "@/lib/session";
import { withDbRetry } from "@/lib/db/retry";

/**
 * Use Node.js runtime for DB connectivity.
 */
export const runtime = "nodejs";

/**
 * Cache the top-rated books to keep the landing page fast.
 */
const getCachedTopBooks = unstable_cache(
  async () => {
    return (await withDbRetry(
      () =>
        db
          .select()
          .from(books)
          .where(eq(books.isActive, true))
          .orderBy(desc(books.rating), desc(books.createdAt))
          .limit(7),
      { retries: 2, delayMs: 300 },
    )) as Book[];
  },
  ["library-top-books-v1"],
  {
    revalidate: 30,
    tags: ["books"],
  },
);

/**
 * Library landing page showing top picks and recommendations.
 */
const Page = async () => {
  const session = await getSession();

  if (!session?.user?.id) {
    redirect("/sign-in");
  }
  const userId = session.user.id;
  const firstName = session.user.name?.split(" ")[0] || "reader";

  let topBooks: Book[] = [];
  try {
    topBooks = await getCachedTopBooks();
  } catch (error) {
    console.error("Failed to fetch books for /library:", error);
    return (
      <section className="mx-auto w-full max-w-3xl rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card)] p-6 text-center sm:p-8">
        <h1 className="font-serif text-3xl font-normal tracking-tight text-[var(--mundia-ink)] sm:text-4xl">
          Library
        </h1>
        <p className="mt-3 text-sm text-slate-600 sm:text-base">
          We could not reach the database right now. Please retry in a moment.
        </p>
        <div className="mt-4">
          <Button
            asChild
            className="min-h-12 rounded-lg bg-[var(--mundia-navy)] px-6 text-white hover:bg-[var(--mundia-navy-strong)]"
          >
            <Link href="/all-books">Try All Books</Link>
          </Button>
        </div>
      </section>
    );
  }

  if (topBooks.length === 0) {
    return (
      <section className="mx-auto w-full max-w-3xl rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card)] p-6 text-center sm:p-8">
        <h1 className="font-serif text-3xl font-normal tracking-tight text-[var(--mundia-ink)] sm:text-4xl">
          Library
        </h1>
        <p className="mt-3 text-sm text-slate-600 sm:text-base">
          No books are currently available in the catalog.
        </p>
      </section>
    );
  }

  const featuredBook = topBooks[0];
  const recommendations =
    topBooks.slice(1, 7).length > 0 ? topBooks.slice(1, 7) : topBooks;
  const featuredRating =
    typeof featuredBook.rating === "number"
      ? featuredBook.rating.toFixed(1)
      : String(featuredBook.rating ?? "N/A");

  const rawUserBorrows = await withDbRetry(
    () =>
      db
        .select({
          id: borrowRecords.id,
          userId: borrowRecords.userId,
          bookId: borrowRecords.bookId,
          borrowDate: borrowRecords.borrowDate,
          dueDate: borrowRecords.dueDate,
          returnDate: borrowRecords.returnDate,
          status: borrowRecords.status,
          borrowedBy: borrowRecords.borrowedBy,
          returnedBy: borrowRecords.returnedBy,
          fineAmount: borrowRecords.fineAmount,
          notes: borrowRecords.notes,
          renewalCount: borrowRecords.renewalCount,
          lastReminderSent: borrowRecords.lastReminderSent,
          updatedAt: borrowRecords.updatedAt,
          updatedBy: borrowRecords.updatedBy,
          createdAt: borrowRecords.createdAt,
        })
        .from(borrowRecords)
        .where(
          and(
            eq(borrowRecords.userId, userId),
            eq(borrowRecords.bookId, featuredBook.id),
          ),
        )
        .orderBy(desc(borrowRecords.createdAt)),
    { retries: 1, delayMs: 250 },
  ).catch((error) => {
    console.error("Failed to fetch user borrow records for /library:", error);
    return [];
  });

  // Normalize date/fine fields for client consumption.
  const initialUserBorrows = rawUserBorrows.map((record) => ({
    id: record.id,
    userId: record.userId,
    bookId: record.bookId,
    borrowDate: record.borrowDate,
    dueDate: record.dueDate ? String(record.dueDate) : null,
    returnDate: record.returnDate ? String(record.returnDate) : null,
    status: record.status,
    borrowedBy: record.borrowedBy,
    returnedBy: record.returnedBy,
    fineAmount: record.fineAmount ? String(record.fineAmount) : "0.00",
    notes: record.notes,
    renewalCount: record.renewalCount,
    lastReminderSent: record.lastReminderSent,
    updatedAt: record.updatedAt,
    updatedBy: record.updatedBy,
    createdAt: record.createdAt,
  }));

  return (
    <section className="space-y-10 sm:space-y-12">
      <section className="library-hero">
        <div className="library-hero-grid">
          <div className="library-hero-panel">
            <p className="library-subtitle">Mundiapolis Library</p>
            <h1 className="library-hero-title">Welcome back, {firstName}</h1>
            <p className="library-hero-copy">
              Search the collection, check availability, and keep your borrowed
              books in view before your next class.
            </p>

            <div className="library-hero-actions">
              <Button
                asChild
                className="min-h-12 rounded-lg bg-[var(--mundia-navy)] px-6 text-white hover:bg-[var(--mundia-navy-strong)]"
              >
                <Link href="/all-books">Browse All Books</Link>
              </Button>
              <Link href="/my-profile" className="library-hero-link">
                View My Profile
              </Link>
            </div>

            <div className="mt-8 max-w-2xl border-t border-[var(--mundia-line)] pt-4 text-sm text-[var(--mundia-muted)]">
              Today’s featured title is rated{" "}
              <span className="font-semibold text-[var(--mundia-ink)]">
                {featuredRating}
              </span>{" "}
              and has{" "}
              <span className="font-semibold text-[var(--mundia-ink)]">
                {featuredBook.availableCopies}
              </span>{" "}
              copies available.
            </div>
          </div>

          <div className="library-hero-aside">
            <p className="library-featured-label">Featured picks</p>
            <ul className="library-featured-list">
              {topBooks.slice(0, 3).map((book) => {
                const rating =
                  typeof book.rating === "number"
                    ? book.rating.toFixed(1)
                    : String(book.rating ?? "N/A");

                return (
                  <li key={book.id} className="library-featured-item">
                    <div>
                      <p className="library-featured-title">{book.title}</p>
                      <p className="library-featured-author">{book.author}</p>
                    </div>
                    <span className="library-featured-rating">
                      <img
                        src="/icons/star.svg"
                        alt="star"
                        width={16}
                        height={16}
                        className="size-4"
                      />
                      {rating}
                    </span>
                  </li>
                );
              })}
            </ul>
          </div>
        </div>
      </section>

      <BookOverview
        {...(featuredBook as Book)}
        userId={userId}
        initialUserBorrows={initialUserBorrows}
      />

      <HomeRecommendations
        initialRecommendations={recommendations as Book[]}
        userId={userId}
        limit={6}
      />
    </section>
  );
};

export default Page;
