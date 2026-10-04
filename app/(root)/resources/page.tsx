import Link from "next/link";
import {
  LEARNING_RESOURCE_PAGE_SIZE,
  listVerifiedLearningResources,
} from "@/lib/learning-resources/queries";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const Page = async ({
  searchParams,
}: {
  searchParams: Promise<{ search?: string; category?: string; page?: string }>;
}) => {
  const params = await searchParams;
  const search = (params.search ?? "").trim().slice(0, 100);
  const category = (params.category ?? "").trim().slice(0, 128);
  const page = Math.max(1, Math.min(1_000, Number(params.page) || 1));
  const result = await listVerifiedLearningResources({ search, category, page });
  const totalPages = Math.max(
    1,
    Math.ceil(result.total / LEARNING_RESOURCE_PAGE_SIZE),
  );
  const hrefForPage = (nextPage: number) => {
    const query = new URLSearchParams();
    if (search) query.set("search", search);
    if (category) query.set("category", category);
    if (nextPage > 1) query.set("page", String(nextPage));
    const serialized = query.toString();
    return serialized ? `/resources?${serialized}` : "/resources";
  };

  return (
    <div className="mx-auto w-full max-w-[1200px] py-1 sm:py-3">
      <header className="border-b border-[var(--mundia-line)] pb-6">
        <nav className="mb-5 flex gap-6 text-sm" aria-label="Catalog sections">
          <Link
            href="/all-books"
            className="min-h-11 border-b-2 border-transparent py-3 font-medium text-[var(--mundia-muted)] hover:text-[var(--mundia-ink)]"
          >
            Library books
          </Link>
          <span className="min-h-11 border-b-2 border-[var(--mundia-navy)] py-3 font-semibold text-[var(--mundia-navy)]">
            Open learning
          </span>
        </nav>
        <h1 className="font-serif text-3xl font-normal text-[var(--mundia-ink)] sm:text-4xl">
          Open learning resources
        </h1>
        <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--mundia-muted)] sm:text-base">
          Rights-verified books and course material from authoritative open
          sources. Every item keeps its original licence and attribution.
        </p>
      </header>

      <form
        action="/resources"
        className="my-6 grid gap-3 sm:grid-cols-[minmax(0,1fr)_18rem_auto]"
      >
        <label className="sr-only" htmlFor="resource-search">
          Search learning resources
        </label>
        <input
          id="resource-search"
          type="search"
          name="search"
          defaultValue={search}
          maxLength={100}
          placeholder="Search title, author, or subject"
          className="catalog-field min-h-12"
        />
        <label className="sr-only" htmlFor="resource-category">
          Category
        </label>
        <select
          id="resource-category"
          name="category"
          defaultValue={category}
          className="catalog-field min-h-12"
        >
          <option value="">All categories</option>
          {result.categories.map((item) => (
            <option key={item} value={item}>
              {item}
            </option>
          ))}
        </select>
        <button type="submit" className="catalog-action min-h-12 px-6">
          Search
        </button>
      </form>

      <div className="mb-3 flex items-baseline justify-between gap-4 border-b border-[var(--mundia-line)] pb-3">
        <p className="text-sm text-[var(--mundia-muted)]" aria-live="polite">
          {result.total} verified {result.total === 1 ? "resource" : "resources"}
        </p>
        {(search || category) && (
          <Link
            href="/resources"
            className="text-sm font-semibold text-[var(--mundia-navy)] hover:underline"
          >
            Clear filters
          </Link>
        )}
      </div>

      {result.items.length === 0 ? (
        <section className="border border-[var(--mundia-line)] bg-[var(--mundia-surface)] px-5 py-12 text-center">
          <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">
            No verified resources found
          </h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-[var(--mundia-muted)]">
            Broaden your search or try another category. Unverified material is
            never shown here.
          </p>
        </section>
      ) : (
        <ul className="divide-y divide-[var(--mundia-line)] border-y border-[var(--mundia-line)]">
          {result.items.map((resource) => (
            <li key={resource.id}>
              <Link
                href={`/resources/${resource.id}`}
                className="group grid min-h-28 gap-3 px-1 py-5 transition-colors hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--mundia-navy)] sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center sm:px-4"
              >
                <div className="min-w-0">
                  <p className="text-xs font-medium text-[var(--mundia-gold-strong)]">
                    {resource.category}
                  </p>
                  <h2 className="mt-1 font-serif text-xl text-[var(--mundia-ink)] group-hover:text-[var(--mundia-navy)]">
                    {resource.title}
                  </h2>
                  <p className="mt-1 text-sm text-[var(--mundia-muted)]">
                    {resource.author || "Author not listed"}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <span className="border border-[var(--mundia-line)] px-2.5 py-1.5 text-[var(--mundia-ink)]">
                    {resource.licenseExpression}
                  </span>
                  <span className="text-[var(--mundia-muted)]">
                    {resource.downloadUrl ? "Direct download" : "Official source"}
                  </span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {totalPages > 1 && (
        <nav
          className="mt-8 flex items-center justify-center gap-3"
          aria-label="Learning resource pages"
        >
          {page > 1 ? (
            <Link className="catalog-page-link px-4" href={hrefForPage(page - 1)}>
              Previous
            </Link>
          ) : (
            <span className="catalog-page-link px-4 opacity-45">Previous</span>
          )}
          <span className="text-sm text-[var(--mundia-muted)]">
            Page {Math.min(page, totalPages)} of {totalPages}
          </span>
          {page < totalPages ? (
            <Link className="catalog-page-link px-4" href={hrefForPage(page + 1)}>
              Next
            </Link>
          ) : (
            <span className="catalog-page-link px-4 opacity-45">Next</span>
          )}
        </nav>
      )}
    </div>
  );
};

export default Page;
