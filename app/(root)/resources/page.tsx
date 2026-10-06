import Link from "next/link";
import {
  LEARNING_RESOURCE_PAGE_SIZE,
  listVerifiedLearningResources,
} from "@/lib/learning-resources/queries";
import { LearningResourceCover } from "@/components/LearningResourceCover";
import { formatLearningResourceText } from "@/lib/learning-resources/display-text";

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
  const result = await listVerifiedLearningResources({
    search,
    category,
    page,
  });
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
          {result.total} verified{" "}
          {result.total === 1 ? "resource" : "resources"}
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
        <ul className="divide-y divide-[var(--mundia-line)] border-y border-[var(--mundia-line)] bg-[var(--mundia-surface)]">
          {result.items.map((resource) => (
            <li
              key={resource.id}
              className="group grid grid-cols-[5rem_minmax(0,1fr)] gap-x-4 gap-y-3 px-3 py-5 transition-colors hover:bg-[var(--mundia-panel)] sm:grid-cols-[7rem_minmax(0,1fr)_9.5rem] sm:gap-x-6 sm:px-5 sm:py-6"
            >
              <Link
                href={`/resources/${resource.id}`}
                aria-label={`View ${formatLearningResourceText(resource.title)}`}
                className="focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)]"
              >
                <LearningResourceCover
                  title={resource.title}
                  category={resource.category}
                  sourceName={resource.sourceName}
                  sourceUrl={resource.sourceUrl}
                  coverUrl={resource.coverUrl}
                  coverAlt={resource.coverAlt}
                />
              </Link>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                  <span className="font-semibold text-[var(--mundia-gold-strong)]">
                    {resource.category}
                  </span>
                  <span className="text-[var(--mundia-muted)]" aria-hidden="true">
                    ·
                  </span>
                  <span className="text-[var(--mundia-muted)]">
                    {resource.licenseExpression}
                  </span>
                </div>
                <h2 className="mt-1.5 line-clamp-2 max-w-[48rem] font-serif text-xl leading-[1.25] text-[var(--mundia-ink)] group-hover:text-[var(--mundia-navy)]">
                  <Link
                    href={`/resources/${resource.id}`}
                    className="focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)]"
                  >
                    {formatLearningResourceText(resource.title)}
                  </Link>
                </h2>
                <p className="mt-1 text-sm text-[var(--mundia-muted)]">
                  {resource.author
                    ? formatLearningResourceText(resource.author)
                    : "Author not listed"}
                </p>
                {resource.description && (
                  <p className="mt-2 line-clamp-2 max-w-[65ch] text-sm leading-5 text-[var(--mundia-muted)]">
                    {formatLearningResourceText(resource.description)}
                  </p>
                )}
                <p className="mt-2 truncate text-xs text-[var(--mundia-muted)] sm:hidden">
                  Source: {formatLearningResourceText(resource.sourceName)}
                </p>
              </div>
              <div className="col-start-2 flex items-center gap-3 self-end text-xs sm:col-start-auto sm:flex-col sm:items-stretch sm:self-center">
                {resource.downloadUrl ? (
                  <a
                    href={`/api/learning-resources/${resource.id}/download`}
                    download
                    className="inline-flex min-h-10 items-center justify-center bg-[var(--mundia-navy)] px-4 text-sm font-semibold text-[var(--mundia-surface)] transition-colors hover:bg-[var(--mundia-navy-strong)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] focus-visible:ring-offset-2"
                    aria-label={`Download ${formatLearningResourceText(resource.title)} from ${formatLearningResourceText(resource.sourceName)}`}
                  >
                    Download
                  </a>
                ) : (
                  <Link
                    href={`/resources/${resource.id}`}
                    className="inline-flex min-h-10 items-center justify-center border border-[var(--mundia-navy)] px-4 text-sm font-semibold text-[var(--mundia-navy)] transition-colors hover:bg-[var(--mundia-surface)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] focus-visible:ring-offset-2"
                  >
                    Read at source
                  </Link>
                )}
                <span className="hidden text-center leading-4 text-[var(--mundia-muted)] sm:block">
                  From {formatLearningResourceText(resource.sourceName)}
                </span>
              </div>
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
            <Link
              className="catalog-page-link px-4"
              href={hrefForPage(page - 1)}
            >
              Previous
            </Link>
          ) : (
            <span className="catalog-page-link px-4 opacity-45">Previous</span>
          )}
          <span className="text-sm text-[var(--mundia-muted)]">
            Page {Math.min(page, totalPages)} of {totalPages}
          </span>
          {page < totalPages ? (
            <Link
              className="catalog-page-link px-4"
              href={hrefForPage(page + 1)}
            >
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
