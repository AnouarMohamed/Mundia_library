import { redirect } from "next/navigation";
import { desc, eq, sql } from "drizzle-orm";
import { db } from "@/database/drizzle";
import { bookReviews, books, borrowRecords, users } from "@/database/schema";
import MyProfileTabs from "@/components/MyProfileTabs";
import { getSession } from "@/lib/session";

export const runtime = "nodejs";

const Page = async () => {
  const session = await getSession();

  if (!session?.user?.id) {
    redirect("/sign-in");
  }

  const [currentUser, borrowRows, reviewCountResult] = await Promise.all([
    db
      .select({
        id: users.id,
        fullName: users.fullName,
        email: users.email,
        status: users.status,
        role: users.role,
      })
      .from(users)
      .where(eq(users.id, session.user.id))
      .limit(1)
      .then((rows) => rows[0]),
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
        book: {
          id: books.id,
          title: books.title,
          author: books.author,
          genre: books.genre,
          rating: books.rating,
          totalCopies: books.totalCopies,
          availableCopies: books.availableCopies,
          description: books.description,
          coverColor: books.coverColor,
          coverUrl: books.coverUrl,
          videoUrl: books.videoUrl,
          summary: books.summary,
          isbn: books.isbn,
          publicationYear: books.publicationYear,
          publisher: books.publisher,
          language: books.language,
          pageCount: books.pageCount,
          edition: books.edition,
          isActive: books.isActive,
          createdAt: books.createdAt,
          updatedAt: books.updatedAt,
          updatedBy: books.updatedBy,
        },
      })
      .from(borrowRecords)
      .innerJoin(books, eq(borrowRecords.bookId, books.id))
      .where(eq(borrowRecords.userId, session.user.id))
      .orderBy(desc(borrowRecords.createdAt)),
    db
      .select({ count: sql<number>`count(*)` })
      .from(bookReviews)
      .where(eq(bookReviews.userId, session.user.id)),
  ]);

  if (!currentUser) {
    redirect("/sign-in");
  }

  const transformedBorrows = borrowRows.map((record) => ({
    id: record.id,
    userId: record.userId,
    bookId: record.bookId,
    borrowDate: record.borrowDate || new Date(),
    dueDate: record.dueDate ? new Date(record.dueDate) : null,
    returnDate: record.returnDate ? new Date(record.returnDate) : null,
    status: record.status,
    borrowedBy: record.borrowedBy,
    returnedBy: record.returnedBy,
    fineAmount: record.fineAmount ? Number(record.fineAmount) : 0,
    notes: record.notes,
    renewalCount: record.renewalCount,
    lastReminderSent: record.lastReminderSent,
    updatedAt: record.updatedAt,
    updatedBy: record.updatedBy,
    createdAt: record.createdAt,
    book: record.book,
  }));

  const initialActiveBorrows = transformedBorrows.filter(
    (record) => record.status === "BORROWED"
  );
  const initialPendingRequests = transformedBorrows.filter(
    (record) => record.status === "PENDING"
  );
  const initialBorrowHistory = transformedBorrows.filter(
    (record) => record.status === "RETURNED"
  );
  const totalReviews = Number(reviewCountResult[0]?.count || 0);

  return (
    <section className="space-y-6">
      <div className="rounded-2xl border border-white/10 bg-[rgba(8,14,22,0.65)] p-5 sm:p-7">
        <p className="text-[11px] uppercase tracking-[0.24em] text-light-200/70 sm:text-xs">
          Account
        </p>
        <h1 className="mt-2 font-bebas-neue text-4xl tracking-[0.08em] text-light-100 sm:text-5xl">
          My Profile
        </h1>
        <p className="mt-2 text-sm text-light-200/80 sm:text-base">
          {currentUser.fullName} ({currentUser.email})
        </p>
      </div>

      <MyProfileTabs
        userId={session.user.id}
        initialActiveBorrows={initialActiveBorrows}
        initialPendingRequests={initialPendingRequests}
        initialBorrowHistory={initialBorrowHistory}
        totalReviews={totalReviews}
      />
    </section>
  );
};

export default Page;
