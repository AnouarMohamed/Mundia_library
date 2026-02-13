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

export const runtime = "nodejs";

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
      { retries: 2, delayMs: 300 }
    )) as Book[];
  },
  ["library-top-books-v1"],
  {
    revalidate: 30,
    tags: ["books"],
  }
);

const Page = async () => {
  const session = await getSession();

  if (!session?.user?.id) {
    redirect("/sign-in");
  }

  let topBooks: Book[] = [];
  try {
    topBooks = await getCachedTopBooks();
  } catch (error) {
    console.error("Failed to fetch books for /library:", error);
    return (
      <section className="mx-auto w-full max-w-3xl rounded-2xl border border-white/10 bg-[rgba(8,14,22,0.65)] p-6 text-center sm:p-8">
        <h1 className="font-bebas-neue text-4xl tracking-[0.08em] text-light-100 sm:text-5xl">
          Library
        </h1>
        <p className="mt-3 text-sm text-light-200/80 sm:text-base">
          We could not reach the database right now. Please retry in a moment.
        </p>
        <div className="mt-4">
          <Button
            asChild
            className="min-h-12 rounded-xl border border-primary/50 bg-primary px-6 text-dark-100 hover:bg-primary/95"
          >
            <Link href="/all-books">Try All Books</Link>
          </Button>
        </div>
      </section>
    );
  }

  if (topBooks.length === 0) {
    return (
      <section className="mx-auto w-full max-w-3xl rounded-2xl border border-white/10 bg-[rgba(8,14,22,0.65)] p-6 text-center sm:p-8">
        <h1 className="font-bebas-neue text-4xl tracking-[0.08em] text-light-100 sm:text-5xl">
          Library
        </h1>
        <p className="mt-3 text-sm text-light-200/80 sm:text-base">
          No books are currently available in the catalog.
        </p>
      </section>
    );
  }

  const featuredBook = topBooks[0];
  const recommendations =
    topBooks.slice(1, 7).length > 0 ? topBooks.slice(1, 7) : topBooks;

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
            eq(borrowRecords.userId, session.user.id),
            eq(borrowRecords.bookId, featuredBook.id)
          )
        )
        .orderBy(desc(borrowRecords.createdAt)),
    { retries: 1, delayMs: 250 }
  ).catch((error) => {
    console.error("Failed to fetch user borrow records for /library:", error);
    return [];
  });

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
    <section className="space-y-8 sm:space-y-10">
      <div className="library">
        <p className="library-subtitle">University Catalog</p>
        <h1 className="library-title">Library</h1>
        <p className="mx-auto mt-3 max-w-2xl text-sm text-light-200/85 sm:text-base">
          Browse books, request borrows, and manage your reading activity.
        </p>
        <div className="mt-4 flex justify-center">
          <Button
            asChild
            className="min-h-12 rounded-xl border border-primary/50 bg-primary px-6 text-dark-100 hover:bg-primary/95"
          >
            <Link href="/all-books">Browse All Books</Link>
          </Button>
        </div>
      </div>

      <BookOverview
        {...(featuredBook as Book)}
        userId={session.user.id}
        initialUserBorrows={initialUserBorrows}
      />

      <HomeRecommendations
        initialRecommendations={recommendations as Book[]}
        userId={session.user.id}
        limit={6}
      />
    </section>
  );
};

export default Page;
