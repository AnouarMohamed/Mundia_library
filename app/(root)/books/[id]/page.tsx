import { notFound, redirect } from "next/navigation";
import { and, desc, eq } from "drizzle-orm";
import { db } from "@/database/drizzle";
import {
  bookReviews,
  books,
  borrowRecords,
  users,
} from "@/database/schema";
import BookOverview from "@/components/BookOverview";
import BookDetailContent from "@/components/BookDetailContent";
import { getSession } from "@/lib/session";

export const runtime = "nodejs";

const Page = async ({ params }: { params: Promise<{ id: string }> }) => {
  const session = await getSession();

  if (!session?.user?.id) {
    redirect("/sign-in");
  }

  const { id } = await params;

  const [
    book,
    reviewRows,
    rawUserBorrows,
  ] = await Promise.all([
    db
      .select()
      .from(books)
      .where(eq(books.id, id))
      .limit(1)
      .then((rows) => rows[0]),
    db
      .select({
        id: bookReviews.id,
        rating: bookReviews.rating,
        comment: bookReviews.comment,
        createdAt: bookReviews.createdAt,
        updatedAt: bookReviews.updatedAt,
        userFullName: users.fullName,
        userEmail: users.email,
      })
      .from(bookReviews)
      .innerJoin(users, eq(bookReviews.userId, users.id))
      .where(eq(bookReviews.bookId, id))
      .orderBy(desc(bookReviews.createdAt)),
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
          eq(borrowRecords.bookId, id)
        )
      )
      .orderBy(desc(borrowRecords.createdAt)),
  ]);

  if (!book) {
    notFound();
  }

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

  const hasExistingReview = reviewRows.some(
    (review) => review.userEmail === session.user.email
  );
  const hasReturnedBorrow = rawUserBorrows.some(
    (record) => record.status === "RETURNED"
  );
  const isCurrentlyBorrowed = rawUserBorrows.some(
    (record) => record.status === "BORROWED"
  );
  const canReview = hasReturnedBorrow && !hasExistingReview;

  const initialReviewEligibility = {
    success: true,
    canReview,
    hasExistingReview,
    isCurrentlyBorrowed,
    reason: hasExistingReview
      ? "You have already reviewed this book"
      : !hasReturnedBorrow
        ? "You must have borrowed this book to review it"
        : "You can review this book",
  };

  return (
    <section className="space-y-8 sm:space-y-10">
      <BookOverview
        {...(book as Book)}
        userId={session.user.id}
        isDetailPage={true}
        initialUserBorrows={initialUserBorrows}
        initialReviewEligibility={initialReviewEligibility}
      />

      <BookDetailContent
        bookId={id}
        userId={session.user.id}
        userEmail={session.user.email || undefined}
        initialBook={book as Book}
        initialReviews={reviewRows}
      />
    </section>
  );
};

export default Page;
