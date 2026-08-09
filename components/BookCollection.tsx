import Link from "next/link";
import BookCard from "@/components/BookCard";

interface BookCollectionProps {
  initialBooks: Book[];
  initialGenres: string[];
  initialPagination: {
    currentPage: number;
    totalPages: number;
    totalBooks: number;
    booksPerPage: number;
  };
  initialSearchParams: {
    search: string;
    genre: string;
    availability: string;
    rating: string;
    sort: string;
    page: number;
  };
}

const createCatalogHref = (
  params: BookCollectionProps["initialSearchParams"],
  updates: Partial<BookCollectionProps["initialSearchParams"]>,
) => {
  const next = { ...params, ...updates };
  const query = new URLSearchParams();

  if (next.search) query.set("search", next.search);
  if (next.genre) query.set("genre", next.genre);
  if (next.availability) query.set("availability", next.availability);
  if (next.rating) query.set("rating", next.rating);
  if (next.sort && next.sort !== "title") query.set("sort", next.sort);
  if (next.page > 1) query.set("page", String(next.page));

  const serialized = query.toString();
  return serialized ? `/all-books?${serialized}` : "/all-books";
};

const BookCollection = ({
  initialBooks: books,
  initialGenres: genres,
  initialPagination: pagination,
  initialSearchParams: params,
}: BookCollectionProps) => {
  const hasActiveFilters = Boolean(
    params.search || params.genre || params.availability || params.rating,
  );
  const firstResult =
    pagination.totalBooks === 0
      ? 0
      : (pagination.currentPage - 1) * pagination.booksPerPage + 1;
  const lastResult = Math.min(
    pagination.currentPage * pagination.booksPerPage,
    pagination.totalBooks,
  );
  const visiblePages = Array.from(
    { length: Math.min(5, pagination.totalPages) },
    (_, index) => {
      const windowStart = Math.min(
        Math.max(1, pagination.currentPage - 2),
        Math.max(1, pagination.totalPages - 4),
      );
      return windowStart + index;
    },
  );

  return (
    <div className="mx-auto w-full max-w-[1500px] py-1 sm:py-3">
      <header className="mb-4 border-b border-[var(--mundia-line)] pb-5 sm:mb-6 sm:pb-7">
        <p className="text-sm text-[var(--mundia-muted)]">Explore the catalog</p>
        <h1 className="mt-1 font-serif text-3xl font-normal tracking-tight text-[var(--mundia-ink)] sm:text-4xl">
          Book collection
        </h1>
        <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--mundia-muted)] sm:text-base">
          Search {pagination.totalBooks} titles and check live availability before
          visiting the library.
        </p>
      </header>

      <form
        action="/all-books"
        method="get"
        className="catalog-search mb-4 sm:mb-6"
      >
        <div className="flex flex-col gap-2 sm:flex-row">
          <label htmlFor="catalog-search" className="sr-only">
            Search by title or author
          </label>
          <input
            id="catalog-search"
            name="search"
            type="search"
            defaultValue={params.search}
            placeholder="Search by title or author"
            enterKeyHint="search"
            autoComplete="off"
            className="catalog-field h-12 min-h-12 min-w-0 flex-1 px-4 py-3 text-base"
          />
          <button type="submit" className="catalog-action min-h-12 px-6">
            Search catalog
          </button>
        </div>

        <details
          className="catalog-filter-disclosure mt-3"
          open={hasActiveFilters || undefined}
        >
          <summary className="flex min-h-12 cursor-pointer list-none items-center justify-between rounded-lg border border-[var(--mundia-line)] px-4 text-sm font-semibold text-[var(--mundia-ink)] marker:hidden lg:hidden">
            Filters and sorting
            <span aria-hidden="true" className="text-lg leading-none">
              +
            </span>
          </summary>

          <div className="catalog-filter-options mt-3 grid gap-3 border-t border-[var(--mundia-line)] pt-4 sm:grid-cols-2 lg:mt-0 lg:grid-cols-4 lg:border-0 lg:pt-0">
            <label className="space-y-1.5 text-sm font-medium text-[var(--mundia-ink)]">
              <span>Genre</span>
              <select
                name="genre"
                defaultValue={params.genre}
                className="catalog-field"
              >
                <option value="">All genres</option>
                {genres.map((genre) => (
                  <option key={genre} value={genre}>
                    {genre}
                  </option>
                ))}
              </select>
            </label>

            <label className="space-y-1.5 text-sm font-medium text-[var(--mundia-ink)]">
              <span>Availability</span>
              <select
                name="availability"
                defaultValue={params.availability}
                className="catalog-field"
              >
                <option value="">All books</option>
                <option value="available">Available now</option>
                <option value="unavailable">Currently unavailable</option>
              </select>
            </label>

            <label className="space-y-1.5 text-sm font-medium text-[var(--mundia-ink)]">
              <span>Minimum rating</span>
              <select
                name="rating"
                defaultValue={params.rating}
                className="catalog-field"
              >
                <option value="">Any rating</option>
                <option value="5">5 stars</option>
                <option value="4">4+ stars</option>
                <option value="3">3+ stars</option>
                <option value="2">2+ stars</option>
                <option value="1">1+ stars</option>
              </select>
            </label>

            <label className="space-y-1.5 text-sm font-medium text-[var(--mundia-ink)]">
              <span>Sort by</span>
              <select name="sort" defaultValue={params.sort} className="catalog-field">
                <option value="title">Title A–Z</option>
                <option value="author">Author A–Z</option>
                <option value="rating">Highest rated</option>
                <option value="date">Newest first</option>
              </select>
            </label>

            <div className="flex flex-col gap-2 sm:col-span-2 sm:flex-row lg:col-span-4 lg:justify-end">
              {hasActiveFilters && (
                <Link
                  href="/all-books"
                  className="inline-flex min-h-12 items-center justify-center rounded-lg border border-[var(--mundia-line)] px-5 text-sm font-semibold text-[var(--mundia-ink)] transition-colors hover:bg-[var(--mundia-panel)]"
                >
                  Clear filters
                </Link>
              )}
              <button type="submit" className="catalog-action min-h-12 px-6">
                Apply filters
              </button>
            </div>
          </div>
        </details>
      </form>

      <div className="mb-4 flex items-baseline justify-between gap-4 sm:mb-5">
        <p className="text-sm text-[var(--mundia-muted)]" aria-live="polite">
          {pagination.totalBooks === 0
            ? "No matching books"
            : `${firstResult}–${lastResult} of ${pagination.totalBooks} books`}
        </p>
        {hasActiveFilters && (
          <span className="text-xs font-semibold uppercase tracking-wide text-[var(--mundia-gold-strong)]">
            Filtered
          </span>
        )}
      </div>

      {books.length === 0 ? (
        <section className="rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-surface)] px-5 py-10 text-center">
          <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">
            No books found
          </h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-[var(--mundia-muted)]">
            Try a broader title, author, or filter combination.
          </p>
          <Link
            href="/all-books"
            className="mt-5 inline-flex min-h-12 items-center justify-center rounded-lg bg-[var(--mundia-navy)] px-6 text-sm font-semibold text-white"
          >
            Reset catalog
          </Link>
        </section>
      ) : (
        <ul className="book-list mt-0">
          {books.map((book, index) => (
            <BookCard key={book.id} {...book} imagePriority={index < 2} />
          ))}
        </ul>
      )}

      {pagination.totalPages > 1 && (
        <nav
          className="mt-7 flex flex-wrap items-center justify-center gap-2 sm:mt-9"
          aria-label="Catalog pages"
        >
          <Link
            href={createCatalogHref(params, {
              page: Math.max(1, pagination.currentPage - 1),
            })}
            aria-disabled={pagination.currentPage === 1}
            tabIndex={pagination.currentPage === 1 ? -1 : undefined}
            className="catalog-page-link px-4 aria-disabled:pointer-events-none aria-disabled:opacity-45"
          >
            Previous
          </Link>

          {visiblePages.map((pageNumber) => (
            <Link
              key={pageNumber}
              href={createCatalogHref(params, { page: pageNumber })}
              aria-current={
                pageNumber === pagination.currentPage ? "page" : undefined
              }
              className="catalog-page-link min-w-12 px-3 aria-[current=page]:border-[var(--mundia-navy)] aria-[current=page]:bg-[var(--mundia-navy)] aria-[current=page]:text-white"
            >
              <span className="sr-only">Page </span>
              {pageNumber}
            </Link>
          ))}

          <Link
            href={createCatalogHref(params, {
              page: Math.min(
                pagination.totalPages,
                pagination.currentPage + 1,
              ),
            })}
            aria-disabled={pagination.currentPage === pagination.totalPages}
            tabIndex={
              pagination.currentPage === pagination.totalPages ? -1 : undefined
            }
            className="catalog-page-link px-4 aria-disabled:pointer-events-none aria-disabled:opacity-45"
          >
            Next
          </Link>
        </nav>
      )}
    </div>
  );
};

export default BookCollection;
