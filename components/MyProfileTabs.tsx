import Link from "next/link";
import BookCover from "@/components/BookCover";
import RenewalRequestButton from "@/components/RenewalRequestButton";
import ReturnBookButton from "@/components/ReturnBookButton";

interface BorrowRecordWithBook {
  id: string;
  userId: string;
  bookId: string;
  borrowDate: Date;
  dueDate: Date | null;
  returnDate?: Date | null;
  status: "PENDING" | "BORROWED" | "RETURNED";
  borrowedBy?: string | null;
  returnedBy?: string | null;
  fineAmount: number;
  notes?: string | null;
  renewalCount: number;
  lastReminderSent?: Date | null;
  updatedAt: Date | null;
  updatedBy?: string | null;
  createdAt: Date | null;
  book: Book;
}

type ProfileTab = "active" | "pending" | "history";

interface MyProfileTabsProps {
  selectedTab: ProfileTab;
  initialActiveBorrows: BorrowRecordWithBook[];
  initialPendingRequests: BorrowRecordWithBook[];
  initialBorrowHistory: BorrowRecordWithBook[];
  totalReviews: number;
}

const formatDate = (date: Date | null | undefined) =>
  date
    ? new Intl.DateTimeFormat("en", {
        day: "numeric",
        month: "short",
        year: "numeric",
        timeZone: "UTC",
      }).format(date)
    : "Not set";

const toUtcDayStart = (date: Date) =>
  Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());

const getDueState = (dueDate: Date | null) => {
  if (!dueDate) return { label: "Due date not set", isOverdue: false };

  const millisecondsPerDay = 86_400_000;
  const days = Math.round(
    (toUtcDayStart(dueDate) - toUtcDayStart(new Date())) / millisecondsPerDay,
  );

  if (days < 0) {
    const overdueDays = Math.abs(days);
    return {
      label: `${overdueDays} day${overdueDays === 1 ? "" : "s"} overdue`,
      isOverdue: true,
    };
  }

  if (days === 0) return { label: "Due today", isOverdue: false };
  return {
    label: `${days} day${days === 1 ? "" : "s"} remaining`,
    isOverdue: false,
  };
};

const EmptyState = ({ tab }: { tab: ProfileTab }) => {
  const copy = {
    active: {
      title: "No active loans",
      body: "Books checked out to you will appear here with their due dates.",
      action: "Browse available books",
    },
    pending: {
      title: "No pending requests",
      body: "Borrow requests waiting for library approval will appear here.",
      action: "Find a book",
    },
    history: {
      title: "No borrowing history",
      body: "Returned books and review opportunities will collect here.",
      action: "Explore the catalog",
    },
  }[tab];

  return (
    <section className="rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-surface)] px-5 py-8 text-center">
      <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">{copy.title}</h2>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-[var(--mundia-muted)]">
        {copy.body}
      </p>
      <Link
        href="/all-books"
        className="mt-5 inline-flex min-h-12 items-center justify-center rounded-lg bg-[var(--mundia-navy)] px-6 text-sm font-semibold text-white"
      >
        {copy.action}
      </Link>
    </section>
  );
};

const BorrowRecordCard = ({ record }: { record: BorrowRecordWithBook }) => {
  const dueState = getDueState(record.dueDate);
  const statusLabel =
    record.status === "BORROWED"
      ? "On loan"
      : record.status === "PENDING"
        ? "Awaiting approval"
        : "Returned";

  return (
    <article className="rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-surface)] p-4 sm:p-5">
      <div className="grid grid-cols-[4.5rem_minmax(0,1fr)] gap-4 sm:grid-cols-[6rem_minmax(0,1fr)] sm:gap-5">
        <Link
          href={`/books/${record.book.id}`}
          className="flex min-h-24 items-start justify-center rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-paper)] p-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)]"
          aria-label={`Open ${record.book.title}`}
        >
          <BookCover
            variant="small"
            className="h-20 w-14 sm:h-28 sm:w-20"
            coverColor={record.book.coverColor}
            coverImage={record.book.coverUrl}
            title={record.book.title}
          />
        </Link>

        <div className="min-w-0">
          <p className="text-xs font-semibold uppercase tracking-wide text-[var(--mundia-gold-strong)]">
            {statusLabel}
          </p>
          <h2 className="mt-1 line-clamp-2 font-serif text-xl leading-tight text-[var(--mundia-ink)] sm:text-2xl">
            {record.book.title}
          </h2>
          <p className="mt-1 line-clamp-1 text-sm text-[var(--mundia-muted)]">
            {record.book.author}
          </p>

          {record.status === "BORROWED" && (
            <div className="mt-3">
              <p
                className={`text-sm font-semibold ${
                  dueState.isOverdue
                    ? "text-[var(--mundia-danger)]"
                    : "text-[var(--mundia-ink)]"
                }`}
              >
                {dueState.label}
              </p>
              <p className="mt-0.5 text-xs text-[var(--mundia-muted)]">
                Due {formatDate(record.dueDate)}
              </p>
            </div>
          )}

          {record.status === "PENDING" && (
            <p className="mt-3 text-sm text-[var(--mundia-muted)]">
              Requested {formatDate(record.createdAt)}
            </p>
          )}

          {record.status === "RETURNED" && (
            <div className="mt-3 text-sm text-[var(--mundia-muted)]">
              <p>Returned {formatDate(record.returnDate)}</p>
              {record.fineAmount > 0 && (
                <p className="mt-1 font-semibold text-[var(--mundia-danger)]">
                  Fine recorded: ${record.fineAmount.toFixed(2)}
                </p>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="mt-4 flex flex-col gap-2 border-t border-[var(--mundia-line)] pt-4 sm:flex-row sm:flex-wrap">
        {record.status === "BORROWED" && (
          <>
            <ReturnBookButton
              recordId={record.id}
              bookTitle={record.book.title}
              dueDate={record.dueDate}
            />
            <RenewalRequestButton
              borrowRecordId={record.id}
              bookTitle={record.book.title}
            />
          </>
        )}
        <Link
          href={`/books/${record.book.id}`}
          className="inline-flex min-h-12 items-center justify-center rounded-lg border border-[var(--mundia-line)] px-4 text-sm font-semibold text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)]"
        >
          {record.status === "RETURNED" ? "Read or review" : "View book"}
        </Link>
      </div>
    </article>
  );
};

const MyProfileTabs = ({
  selectedTab,
  initialActiveBorrows,
  initialPendingRequests,
  initialBorrowHistory,
  totalReviews,
}: MyProfileTabsProps) => {
  const tabs = [
    { value: "active" as const, label: "Loans", count: initialActiveBorrows.length },
    {
      value: "pending" as const,
      label: "Requests",
      count: initialPendingRequests.length,
    },
    { value: "history" as const, label: "History", count: initialBorrowHistory.length },
  ];
  const records =
    selectedTab === "active"
      ? initialActiveBorrows
      : selectedTab === "pending"
        ? initialPendingRequests
        : initialBorrowHistory;
  const totalBorrows =
    initialActiveBorrows.length + initialBorrowHistory.length;

  return (
    <div>
      <dl className="mb-6 grid grid-cols-3 border-y border-[var(--mundia-line)] py-4">
        <div>
          <dt className="text-xs text-[var(--mundia-muted)]">Active</dt>
          <dd className="mt-1 text-xl font-semibold text-[var(--mundia-ink)]">
            {initialActiveBorrows.length}
          </dd>
        </div>
        <div className="border-x border-[var(--mundia-line)] px-4">
          <dt className="text-xs text-[var(--mundia-muted)]">All loans</dt>
          <dd className="mt-1 text-xl font-semibold text-[var(--mundia-ink)]">
            {totalBorrows}
          </dd>
        </div>
        <div className="pl-4">
          <dt className="text-xs text-[var(--mundia-muted)]">Reviews</dt>
          <dd className="mt-1 text-xl font-semibold text-[var(--mundia-ink)]">
            {totalReviews}
          </dd>
        </div>
      </dl>

      <nav aria-label="Borrowing record views">
        <ul className="grid grid-cols-3 border-b border-[var(--mundia-line)]">
          {tabs.map((tab) => (
            <li key={tab.value}>
              <Link
                href={tab.value === "active" ? "/my-profile" : `/my-profile?tab=${tab.value}`}
                aria-current={selectedTab === tab.value ? "page" : undefined}
                className="flex min-h-14 items-center justify-center gap-1 border-b-2 border-transparent px-2 text-sm font-semibold text-[var(--mundia-muted)] aria-[current=page]:border-[var(--mundia-navy)] aria-[current=page]:text-[var(--mundia-navy)]"
              >
                {tab.label}
                <span className="text-xs">({tab.count})</span>
              </Link>
            </li>
          ))}
        </ul>
      </nav>

      <div className="mt-5 space-y-4">
        {records.length === 0 ? (
          <EmptyState tab={selectedTab} />
        ) : (
          records.map((record) => (
            <BorrowRecordCard key={record.id} record={record} />
          ))
        )}
      </div>
    </div>
  );
};

export default MyProfileTabs;
