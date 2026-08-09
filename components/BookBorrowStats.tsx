interface BookBorrowStatsProps {
  availableCopies: number;
  initialStats: {
    totalBorrows: number;
    activeBorrows: number;
    returnedBorrows: number;
  };
}

const BookBorrowStats = ({
  availableCopies,
  initialStats,
}: BookBorrowStatsProps) => (
  <dl className="grid grid-cols-2 gap-x-6 gap-y-4 pb-4 pt-2 sm:grid-cols-4">
    <div>
      <dt className="text-sm text-[var(--mundia-muted)]">Total borrows</dt>
      <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
        {initialStats.totalBorrows}
      </dd>
    </div>
    <div>
      <dt className="text-sm text-[var(--mundia-muted)]">Active loans</dt>
      <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
        {initialStats.activeBorrows}
      </dd>
    </div>
    <div>
      <dt className="text-sm text-[var(--mundia-muted)]">Copies ready</dt>
      <dd className="mt-1 text-lg font-semibold text-[var(--mundia-success-strong)]">
        {availableCopies}
      </dd>
    </div>
    <div>
      <dt className="text-sm text-[var(--mundia-muted)]">Returned</dt>
      <dd className="mt-1 text-lg font-semibold text-[var(--mundia-ink)]">
        {initialStats.returnedBorrows}
      </dd>
    </div>
  </dl>
);

export default BookBorrowStats;
