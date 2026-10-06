import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ApiError, getLearningResourceCategories, searchLearningResources, type LearningResourcePage } from "../api/client";
import { ResourceCover } from "../components/ResourceCover";

const PAGE_SIZE = 24;

export function LearningResourcesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = (searchParams.get("query") ?? "").trim().slice(0, 200);
  const category = (searchParams.get("category") ?? "").trim().slice(0, 128);
  const page = boundedPage(searchParams.get("page"));
  const [result, setResult] = useState<LearningResourcePage | null>(null);
  const [categories, setCategories] = useState<string[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setResult(null);
    setFailed(false);
    Promise.all([
      searchLearningResources({ query, category, page, limit: PAGE_SIZE }),
      getLearningResourceCategories(),
    ]).then(([resources, values]) => {
      if (!controller.signal.aborted) {
        setResult(resources);
        setCategories(values);
      }
    }).catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 401) window.location.assign("/oauth2/authorization/institutional");
      else if (!controller.signal.aborted) setFailed(true);
    });
    return () => controller.abort();
  }, [query, category, page]);

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    const nextQuery = String(data.get("query") ?? "").trim();
    const nextCategory = String(data.get("category") ?? "").trim();
    if (nextQuery) next.set("query", nextQuery);
    if (nextCategory) next.set("category", nextCategory);
    setSearchParams(next);
  }

  return (
    <div className="page-shell">
      <header className="page-heading">
        <p className="eyebrow">Verified open access</p>
        <h1>Open learning resources</h1>
        <p>Books and course material from authoritative sources, with the licence shown before you open or download anything.</p>
      </header>
      <form className="resource-filters" onSubmit={submit}>
        <label><span>Search</span><input type="search" name="query" defaultValue={query} maxLength={200} placeholder="Title, author, or subject" /></label>
        <label><span>Category</span><select name="category" defaultValue={category}><option value="">All categories</option>{categories.map((item) => <option key={item}>{item}</option>)}</select></label>
        <button type="submit">Search</button>
      </form>
      {failed ? <LoadError /> : !result ? <ResourceSkeleton /> : (
        <>
          <div className="result-summary" aria-live="polite"><span>{result.total} verified {result.total === 1 ? "resource" : "resources"}</span>{(query || category) && <Link to="/resources">Clear filters</Link>}</div>
          {result.resources.length === 0 ? <EmptyResults /> : <ResourceList result={result} />}
          <Pagination page={page} totalPages={result.totalPages} query={query} category={category} />
        </>
      )}
    </div>
  );
}

function ResourceList({ result }: { result: LearningResourcePage }) {
  return <ul className="resource-list">{result.resources.map((resource) => (
    <li key={resource.resourceId}>
      <Link className="cover-link" to={`/resources/${resource.resourceId}`}><ResourceCover resource={resource} /></Link>
      <div className="resource-copy">
        <div className="resource-meta"><span>{resource.category}</span><span>{resource.licenseExpression}</span></div>
        <h2><Link to={`/resources/${resource.resourceId}`}>{resource.title}</Link></h2>
        <p className="resource-author">{resource.author ?? "Author not listed"}</p>
        {resource.description && <p className="resource-description">{resource.description}</p>}
      </div>
      <div className="resource-action"><Link to={`/resources/${resource.resourceId}`}>{resource.accessMode === "DOWNLOAD" ? "View and download" : "View resource"}</Link><span>From {resource.sourceName}</span></div>
    </li>
  ))}</ul>;
}

function Pagination({ page, totalPages, query, category }: { page: number; totalPages: number; query: string; category: string }) {
  if (totalPages <= 1) return null;
  const href = (value: number) => {
    const params = new URLSearchParams();
    if (query) params.set("query", query);
    if (category) params.set("category", category);
    if (value > 0) params.set("page", String(value));
    return `/resources?${params}`;
  };
  return <nav className="pagination" aria-label="Learning resource pages">{page > 0 ? <Link to={href(page - 1)}>Previous</Link> : <span>Previous</span>}<p>Page {page + 1} of {totalPages}</p>{page + 1 < totalPages ? <Link to={href(page + 1)}>Next</Link> : <span>Next</span>}</nav>;
}

function boundedPage(value: string | null) { const parsed = Number(value ?? "0"); return Number.isSafeInteger(parsed) ? Math.min(10_000, Math.max(0, parsed)) : 0; }
function LoadError() { return <section className="empty-state" role="alert"><h2>Resources could not be loaded.</h2><p>Try refreshing in a moment. No unverified material has been shown.</p></section>; }
function EmptyResults() { return <section className="empty-state"><h2>No verified resources found.</h2><p>Broaden your search or choose another category.</p></section>; }
function ResourceSkeleton() { return <div className="resource-skeleton" aria-label="Loading resources">{Array.from({ length: 5 }, (_, index) => <span key={index} />)}</div>; }
