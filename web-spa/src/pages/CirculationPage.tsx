import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  ApiError,
  getCatalogEditions,
  getCirculationOverview,
  getLoanPage,
  getReservationPage,
  type CatalogEdition,
  type CirculationEligibility,
  type LoanHistoryItem,
  type Reservation,
} from "../api/client";

type Overview = Awaited<ReturnType<typeof getCirculationOverview>>;
type LoadState = { kind: "loading" } | { kind: "ready"; overview: Overview } | { kind: "failed" | "forbidden" };
type HistoryKind = "loans" | "reservations";

const dateFormatter = new Intl.DateTimeFormat(undefined, { year: "numeric", month: "short", day: "numeric" });

const eligibilityCopy: Record<CirculationEligibility["status"], { label: string; description: string }> = {
  ELIGIBLE: { label: "Eligible to borrow", description: "You can request books and place reservations." },
  INELIGIBLE: { label: "Borrowing unavailable", description: "Resolve the account issue below before requesting books." },
  SUSPENDED: { label: "Borrowing suspended", description: "Contact library staff for help with your account." },
};

const loanLabels: Record<LoanHistoryItem["status"], string> = {
  REQUESTED: "Requested",
  ACTIVE: "On loan",
  RETURNED: "Returned",
  REJECTED: "Not approved",
  CANCELLED: "Cancelled",
};

const reservationLabels: Record<Reservation["status"], string> = {
  WAITING: "Waiting",
  READY: "Ready for pickup",
  FULFILLED: "Collected",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
};

export function CirculationPage() {
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [loadingMore, setLoadingMore] = useState<HistoryKind | null>(null);
  const [pageError, setPageError] = useState<HistoryKind | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void getCirculationOverview(controller.signal)
      .then((overview) => setState({ kind: "ready", overview }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) {
          window.location.assign("/oauth2/authorization/institutional");
          return;
        }
        setState({ kind: error instanceof ApiError && error.status === 403 ? "forbidden" : "failed" });
      });
    return () => controller.abort();
  }, []);

  const editions = useMemo(() => new Map(
    state.kind === "ready" ? state.overview.editions.map((edition) => [edition.editionId, edition]) : [],
  ), [state]);

  if (state.kind === "loading") {
    return <div className="history-skeleton" aria-label="Loading borrowing activity"><span /><span /><span /><span /></div>;
  }
  if (state.kind !== "ready") return <CirculationError forbidden={state.kind === "forbidden"} />;

  const { overview } = state;
  const eligibility = eligibilityCopy[overview.eligibility.status];

  const loadMore = async (kind: HistoryKind) => {
    const currentPage = overview[kind];
    if (!currentPage.nextCursor || loadingMore) return;
    setLoadingMore(kind);
    setPageError(null);
    try {
      const nextPage = kind === "loans"
        ? await getLoanPage(currentPage.nextCursor)
        : await getReservationPage(currentPage.nextCursor);
      if (nextPage.memberId !== overview.eligibility.memberId) throw new ApiError(502);
      const existingIds = new Set(currentPage.items.map((item) =>
        kind === "loans" ? (item as LoanHistoryItem).loanId : (item as Reservation).reservationId));
      const nextIds = nextPage.items.map((item) =>
        kind === "loans" ? (item as LoanHistoryItem).loanId : (item as Reservation).reservationId);
      if (nextIds.some((id) => existingIds.has(id))) throw new ApiError(502);
      const unresolvedEditionIds = Array.from(new Set(nextPage.items
        .map((item) => item.editionId)
        .filter((editionId) => !editions.has(editionId))));
      const nextEditions = await getCatalogEditions(unresolvedEditionIds);
      setState({
        kind: "ready",
        overview: {
          ...overview,
          editions: [...overview.editions, ...nextEditions],
          [kind]: { ...nextPage, items: [...currentPage.items, ...nextPage.items] },
        } as Overview,
      });
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) {
        window.location.assign("/oauth2/authorization/institutional");
        return;
      }
      setPageError(kind);
    } finally {
      setLoadingMore(null);
    }
  };

  return (
    <article className="history-shell">
      <header className="history-heading">
        <p className="eyebrow">My account</p>
        <h1>Borrowing activity</h1>
        <div className={`eligibility-summary eligibility-${overview.eligibility.status.toLowerCase()}`}>
          <strong>{eligibility.label}</strong>
          <span>{eligibility.description}</span>
          {overview.eligibility.reasonCode && <span>{humanizeReason(overview.eligibility.reasonCode)}</span>}
        </div>
      </header>

      <HistorySection
        id="loans"
        title="Loans"
        empty="You have no borrowing history yet."
        items={overview.loans.items}
        editions={editions}
        nextCursor={overview.loans.nextCursor}
        loading={loadingMore === "loans"}
        failed={pageError === "loans"}
        onLoadMore={() => void loadMore("loans")}
        render={(loan, edition) => <LoanRow key={(loan as LoanHistoryItem).loanId} loan={loan as LoanHistoryItem} edition={edition} />}
      />
      <HistorySection
        id="reservations"
        title="Reservations"
        empty="You have no reservation history yet."
        items={overview.reservations.items}
        editions={editions}
        nextCursor={overview.reservations.nextCursor}
        loading={loadingMore === "reservations"}
        failed={pageError === "reservations"}
        onLoadMore={() => void loadMore("reservations")}
        render={(reservation, edition) => <ReservationRow key={(reservation as Reservation).reservationId} reservation={reservation as Reservation} edition={edition} />}
      />
    </article>
  );
}

function HistorySection<T extends { editionId: string }>({
  id, title, empty, items, editions, nextCursor, loading, failed, onLoadMore, render,
}: {
  id: string;
  title: string;
  empty: string;
  items: T[];
  editions: Map<string, CatalogEdition>;
  nextCursor: string | null;
  loading: boolean;
  failed: boolean;
  onLoadMore: () => void;
  render: (item: T, edition: CatalogEdition | undefined) => ReactNode;
}) {
  return (
    <section className="history-section" aria-labelledby={`${id}-title`}>
      <div className="history-section-heading">
        <h2 id={`${id}-title`}>{title}</h2>
        <span>{items.length} shown</span>
      </div>
      {items.length === 0 ? <p className="history-empty">{empty}</p> : (
        <ol className="history-list">{items.map((item) => render(item, editions.get(item.editionId)))}</ol>
      )}
      {failed && <output className="history-page-error">Could not load more. Your existing history is still available.</output>}
      {nextCursor && (
        <button className="secondary-action" type="button" onClick={onLoadMore} disabled={loading}>
          {loading ? "Loading…" : `Load more ${title.toLowerCase()}`}
        </button>
      )}
    </section>
  );
}

function LoanRow({ loan, edition }: { loan: LoanHistoryItem; edition?: CatalogEdition }) {
  const date = loan.status === "ACTIVE" && loan.dueAt
    ? { label: "Due", value: loan.dueAt }
    : loan.status === "RETURNED" && loan.returnedAt
      ? { label: "Returned", value: loan.returnedAt }
      : { label: "Requested", value: loan.requestedAt };
  return (
    <li className="history-row">
      <EditionCover edition={edition} />
      <div className="history-copy">
        <h3>{edition?.title ?? "Catalog record unavailable"}</h3>
        {edition && <p>{edition.publisher} · {edition.publicationYear}</p>}
        <p><span>{date.label}</span> <time dateTime={date.value}>{formatDate(date.value)}</time></p>
      </div>
      <span className={`history-status status-${loan.status.toLowerCase()}`}>{loanLabels[loan.status]}</span>
    </li>
  );
}

function ReservationRow({ reservation, edition }: { reservation: Reservation; edition?: CatalogEdition }) {
  const date = reservation.status === "READY" && reservation.expiresAt
    ? { label: "Collect by", value: reservation.expiresAt }
    : { label: "Placed", value: reservation.placedAt };
  return (
    <li className="history-row">
      <EditionCover edition={edition} />
      <div className="history-copy">
        <h3>{edition?.title ?? "Catalog record unavailable"}</h3>
        {edition && <p>{edition.publisher} · {edition.publicationYear}</p>}
        <p><span>{date.label}</span> <time dateTime={date.value}>{formatDate(date.value)}</time></p>
      </div>
      <span className={`history-status status-${reservation.status.toLowerCase()}`}>{reservationLabels[reservation.status]}</span>
    </li>
  );
}

function EditionCover({ edition }: { edition?: CatalogEdition }) {
  return (
    <div className="history-cover" aria-hidden="true">
      {edition?.coverUrl ? <img src={edition.coverUrl} alt="" loading="lazy" /> : <span>{edition?.title.charAt(0) ?? "?"}</span>}
    </div>
  );
}

function CirculationError({ forbidden }: { forbidden: boolean }) {
  return (
    <section className="status-page">
      <p className="eyebrow">Borrowing activity</p>
      <h1>{forbidden ? "Borrowing history is not available for this account." : "We could not load your borrowing activity."}</h1>
      <p>{forbidden ? "Contact library staff if you believe this is incorrect." : "Nothing was changed. Try again in a moment."}</p>
      {!forbidden && <button type="button" onClick={() => window.location.reload()}>Try again</button>}
    </section>
  );
}

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function humanizeReason(reason: string) {
  return reason.toLowerCase().split("_").map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(" ");
}
