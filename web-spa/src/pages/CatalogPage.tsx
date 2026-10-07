import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  ApiError,
  getCirculationEligibility,
  placeReservation,
  requestLoan,
  searchCatalog,
  type CatalogEdition,
  type CatalogSearch,
  type CirculationEligibility,
} from "../api/client";

const PAGE_SIZE = 20;

type Notice = { editionId: string; tone: "success" | "error"; message: string };

export function CatalogPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = (searchParams.get("query") ?? "").trim().slice(0, 200);
  const availableOnly = searchParams.get("available") === "true";
  const page = boundedPage(searchParams.get("page"));
  const [result, setResult] = useState<CatalogSearch | null>(null);
  const [eligibility, setEligibility] = useState<CirculationEligibility | null>(null);
  const [failed, setFailed] = useState(false);
  const [pendingEdition, setPendingEdition] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setResult(null);
    setFailed(false);
    Promise.all([
      searchCatalog({ query, page, availableOnly, limit: PAGE_SIZE, signal: controller.signal }),
      getCirculationEligibility(controller.signal),
    ]).then(([catalog, memberEligibility]) => {
      if (!controller.signal.aborted) {
        setResult(catalog);
        setEligibility(memberEligibility);
      }
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) window.location.assign("/oauth2/authorization/institutional");
      else setFailed(true);
    });
    return () => controller.abort();
  }, [query, availableOnly, page]);

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    const nextQuery = String(data.get("query") ?? "").trim().slice(0, 200);
    if (nextQuery) next.set("query", nextQuery);
    if (data.get("available") === "true") next.set("available", "true");
    setSearchParams(next);
  }

  async function submitCommand(edition: CatalogEdition) {
    if (!eligibility || eligibility.status !== "ELIGIBLE" || pendingEdition) return;
    setPendingEdition(edition.editionId);
    setNotice(null);
    try {
      const requestingLoan = edition.availableCopies > 0;
      const response = requestingLoan
        ? await requestLoan(edition.editionId, eligibility.memberId)
        : await placeReservation(edition.editionId, eligibility.memberId);
      setNotice({
        editionId: edition.editionId,
        tone: "success",
        message: response.replayed
          ? "Your existing request was confirmed."
          : requestingLoan ? "Borrow request submitted." : "Reservation placed.",
      });
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) {
        window.location.assign("/oauth2/authorization/institutional");
        return;
      }
      if (error instanceof ApiError && (error.status === 409 || error.status === 422)) {
        void getCirculationEligibility().then(setEligibility).catch(() => undefined);
      }
      setNotice({ editionId: edition.editionId, tone: "error", message: commandError(error) });
    } finally {
      setPendingEdition(null);
    }
  }

  return (
    <div className="page-shell">
      <header className="page-heading">
        <p className="eyebrow">Physical collection</p>
        <h1>Find a library book</h1>
        <p>Search current editions, request an available copy, or join the reservation queue.</p>
      </header>
      <form className="catalog-filters" onSubmit={submit}>
        <label><span>Search</span><input key={query} type="search" name="query" defaultValue={query} maxLength={200} placeholder="Title, ISBN, or subject" /></label>
        <label className="filter-check"><input key={String(availableOnly)} type="checkbox" name="available" value="true" defaultChecked={availableOnly} /><span>Available now</span></label>
        <button type="submit">Search</button>
      </form>
      {eligibility && eligibility.status !== "ELIGIBLE" && (
        <output className="catalog-policy">Borrowing is unavailable for this account. Review your borrowing page or contact library staff.</output>
      )}
      {failed ? <LoadError /> : !result || !eligibility ? <CatalogSkeleton /> : (
        <>
          <div className="result-summary" aria-live="polite">
            <span>{result.total} {result.total === 1 ? "edition" : "editions"}</span>
            {(query || availableOnly) && <Link to="/catalog">Clear filters</Link>}
          </div>
          {result.editions.length === 0 ? <EmptyResults /> : (
            <ul className="catalog-list">
              {result.editions.map((edition) => (
                <CatalogRow
                  key={edition.editionId}
                  edition={edition}
                  eligible={eligibility.status === "ELIGIBLE"}
                  pending={pendingEdition === edition.editionId}
                  commandsDisabled={pendingEdition !== null}
                  notice={notice?.editionId === edition.editionId ? notice : null}
                  onCommand={() => void submitCommand(edition)}
                />
              ))}
            </ul>
          )}
          <Pagination page={page} totalPages={result.totalPages} query={query} availableOnly={availableOnly} />
        </>
      )}
    </div>
  );
}

function CatalogRow({ edition, eligible, pending, commandsDisabled, notice, onCommand }: {
  edition: CatalogEdition;
  eligible: boolean;
  pending: boolean;
  commandsDisabled: boolean;
  notice: Notice | null;
  onCommand: () => void;
}) {
  const canCirculate = edition.totalCopies > 0;
  const action = edition.availableCopies > 0 ? "Request book" : "Reserve book";
  return (
    <li>
      <div className="catalog-cover" aria-hidden="true">
        {edition.coverUrl ? <img src={edition.coverUrl} alt="" loading="lazy" /> : <span>{edition.title.charAt(0)}</span>}
      </div>
      <div className="catalog-copy">
        <p>{edition.publisher} · {edition.publicationYear} · {edition.language.toUpperCase()}</p>
        <h2>{edition.title}</h2>
        <p>{edition.pageCount} pages · ISBN {edition.isbn}</p>
      </div>
      <div className="catalog-action">
        <strong>{edition.availableCopies} of {edition.totalCopies} available</strong>
        <button type="button" onClick={onCommand} disabled={!eligible || !canCirculate || commandsDisabled}>
          {pending ? "Submitting…" : canCirculate ? action : "Not in circulation"}
        </button>
        {notice && <output className={`command-notice command-${notice.tone}`}>{notice.message} {notice.tone === "success" && <Link to="/borrowing">View activity</Link>}</output>}
      </div>
    </li>
  );
}

function Pagination({ page, totalPages, query, availableOnly }: { page: number; totalPages: number; query: string; availableOnly: boolean }) {
  if (totalPages <= 1) return null;
  const href = (value: number) => {
    const params = new URLSearchParams();
    if (query) params.set("query", query);
    if (availableOnly) params.set("available", "true");
    if (value > 0) params.set("page", String(value));
    return `/catalog?${params}`;
  };
  return <nav className="pagination" aria-label="Catalog pages">{page > 0 ? <Link to={href(page - 1)}>Previous</Link> : <span>Previous</span>}<p>Page {page + 1} of {totalPages}</p>{page + 1 < totalPages ? <Link to={href(page + 1)}>Next</Link> : <span>Next</span>}</nav>;
}

function commandError(error: unknown) {
  if (!(error instanceof ApiError)) return "The request could not be confirmed. Check your borrowing activity before retrying.";
  if (error.status === 409) return "This book or account changed. Check your borrowing activity before trying again.";
  if (error.status === 422) return "Library policy does not allow this request right now.";
  if (error.status === 403) return "Your account is not permitted to perform this action.";
  return "The request could not be confirmed. Check your borrowing activity before retrying.";
}

function boundedPage(value: string | null) { const parsed = Number(value ?? "0"); return Number.isSafeInteger(parsed) ? Math.min(10_000, Math.max(0, parsed)) : 0; }
function LoadError() { return <section className="empty-state" role="alert"><h2>The catalog could not be loaded.</h2><p>Try refreshing in a moment.</p></section>; }
function EmptyResults() { return <section className="empty-state"><h2>No matching editions found.</h2><p>Broaden your search or include books that are currently checked out.</p></section>; }
function CatalogSkeleton() { return <div className="catalog-skeleton" aria-label="Loading catalog">{Array.from({ length: 5 }, (_, index) => <span key={index} />)}</div>; }
